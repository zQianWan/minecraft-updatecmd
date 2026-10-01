package com.dshwork.updatecmd;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * pp 更新执行器。
 *
 * <p><b>语义（已与使用者确认）</b>：扫描给定区域的每个坐标，
 * <ul>
 *   <li>该坐标是空气 → 跳过（不算核，也不读邻居）；</li>
 *   <li>该坐标不是空气 → 把它的 <b>6 个面邻居（西、东、北、南、下、上）各当作更新核</b>，每个核做一次 PP 更新。</li>
 * </ul>
 * 效果是：区域内每个非空气方块都会<b>收到</b> PP 更新（它的每个邻居都当过一次核，而核会向自己的邻居传播）。
 * 副作用（PP 固有能力，无法只针对单一方向）：这些核同时也会更新它们自己的其它邻居。
 *
 * <p><b>不去重</b>：同一个核被多个坐标请求时每次都执行（已与使用者确认），
 * 因为去重会改变某些方块实际收到的更新次数。
 *
 * <p><b>「一次 PP 更新」</b>= 完整复刻 vanilla {@code Level.setBlock} 的三步（Level.java:238-243）：
 * <pre>
 *   coreState.updateIndirectNeighbourShapes(...)   ← 间接 PP（前）
 *   coreState.updateNeighbourShapes(...)           ← 正常 PP
 *   coreState.updateIndirectNeighbourShapes(...)   ← 间接 PP（后）
 * </pre>
 * flags 与上限取 vanilla 的派生值：{@code updateFlags & -34}（/setblock 的 3 → {@link Block#UPDATE_CLIENTS}=2），
 * 递归上限 {@code 512 - 1 = 511}。间接 PP 对绝大多数方块是空操作
 * （BlockBehaviour.java:131-132），只有红石线覆写它（RedStoneWireBlock.java:167-189）。
 *
 * <p>未加载区块一律跳过、绝不加载，理由同 {@link NcUpdater}。
 */
public final class PpUpdater {
	private PpUpdater() {
	}

	/** vanilla 由 {@code 3 & -34} 得到的形状更新标志位：只通知客户端，不触发 NC 更新。 */
	public static final int PP_UPDATE_FLAGS = Block.UPDATE_CLIENTS;

	/** vanilla {@code setBlock(..., 3, 512)} 派生出的递归上限：{@code 512 - 1}。 */
	public static final int PP_UPDATE_LIMIT = 511;

	/**
	 * 一个方块周围的 6 个面方向，顺序 = vanilla 的 {@code BlockBehaviour.UPDATE_SHAPE_ORDER}
	 * （BlockBehaviour.java:395：西、东、北、南、下、上）。它同时决定：
	 * ① 取哪 6 个邻居当核；② 这 6 个核的执行顺序。
	 */
	static final Direction[] CORE_DIRECTIONS = {
			Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP
	};

	/**
	 * @param scanned         扫描过的坐标数（= 选定区域体积）
	 * @param airSkipped      因是空气而跳过的坐标数
	 * @param unloadedSkipped 因未加载 / 越界而跳过的坐标数
	 * @param coresRun        实际执行的核更新次数
	 * @param coresSkipped    因核所在位置未加载 / 越界而跳过的核次数
	 * @param millis          执行耗时（毫秒）
	 */
	public record Result(long scanned, long airSkipped, long unloadedSkipped,
			long coresRun, long coresSkipped, long millis) {
	}

	private static final class Counters {
		private long scanned;
		private long airSkipped;
		private long unloadedSkipped;
		private long coresRun;
		private long coresSkipped;
	}

	public static Result apply(ServerLevel level, BoxRegion region) {
		return apply(level, region, level::isLoaded);
	}

	/**
	 * 包内可见的重载：把「该位置是否可用」抽成参数，好让无游戏自测用代理 {@link LevelAccessor}
	 * 驱动整段扫描逻辑（空气跳过、核顺序、计数、核越界跳过），而不必构造 {@link ServerLevel}。
	 * 生产路径走上面那个两参版本，行为完全一致。
	 */
	static Result apply(LevelAccessor level, BoxRegion region, Predicate<BlockPos> loaded) {
		Counters counters = new Counters();
		long start = System.nanoTime();

		// 与 nc 侧共用 BoxRegion.forEachPos 的顺序契约：Y 低→高 → X 小→大 → Z 小→大
		region.forEachPos(pos -> {
			counters.scanned++;

			if (!loaded.test(pos)) {
				counters.unloadedSkipped++;
				return;
			}

			if (level.getBlockState(pos).isAir()) {
				counters.airSkipped++;
				return;
			}

			for (Direction direction : CORE_DIRECTIONS) {
				BlockPos core = pos.relative(direction);

				// 核可能落在选定区域之外，甚至落在未加载区块里：先守卫，绝不触发区块加载
				if (!loaded.test(core)) {
					counters.coresSkipped++;
					continue;
				}

				applyPpAtCore(level, core);
				counters.coresRun++;
			}
		});

		long millis = (System.nanoTime() - start) / 1_000_000L;
		return new Result(counters.scanned, counters.airSkipped, counters.unloadedSkipped,
				counters.coresRun, counters.coresSkipped, millis);
	}

	/**
	 * 以 {@code pos} 为更新核做一次 PP 更新。抽成独立方法有两个原因：
	 * 一是与 vanilla 的三步流程一一对应便于核对，二是无游戏自测可以传入
	 * {@link LevelAccessor} 的代理对象，断言调用序列、参数、标志位与上限。
	 */
	static void applyPpAtCore(LevelAccessor level, BlockPos pos) {
		BlockState coreState = level.getBlockState(pos);
		coreState.updateIndirectNeighbourShapes(level, pos, PP_UPDATE_FLAGS, PP_UPDATE_LIMIT);
		coreState.updateNeighbourShapes(level, pos, PP_UPDATE_FLAGS, PP_UPDATE_LIMIT);
		coreState.updateIndirectNeighbourShapes(level, pos, PP_UPDATE_FLAGS, PP_UPDATE_LIMIT);
	}
}
