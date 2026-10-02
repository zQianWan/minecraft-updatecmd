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
 * <p><b>语义（已与使用者确认）</b>：扫描给定区域，
 * <ul>
 *   <li>该坐标是空气 → 跳过；</li>
 *   <li>该坐标不是空气 → 让<b>它自己收到 6 次 PP 更新</b>（西、东、北、南、下、上各一次）。</li>
 * </ul>
 *
 * <p><b>与 1.1.0 的实现差异（1.2.0 的优化）</b>：1.1.0 是把目标方块的 6 个邻居各当成「更新核」，
 * 每个核再跑一遍完整的 PP 流程（即向它自己的 6 个邻居传播）→ 每个目标方块实际产生 6×6 = 36 次形状更新调用，
 * 并且会波及距离 2 的方块。1.2.0 直接对目标方块调用形状更新、把「哪个方向的邻居发生了变化」写进参数，
 * 于是每个目标方块恰好收到 6 次更新：<b>开销降到 1/6，且不再向外波及第二圈</b>。
 *
 * <p><b>不再发出「间接 PP 更新」</b>：vanilla 在方块变化时会额外调用 {@code updateIndirectNeighbourShapes}
 * （默认空实现，只有红石线覆写它来更新斜上/斜下的红石线，BlockBehaviour.java:131-132、
 * RedStoneWireBlock.java:167-189）。新方案里每次调用都是"目标方块自身重算形状"，没有"某个方块变化"这个事件，
 * 因此不再发出间接更新 —— 代价是斜向红石线不会因此被刷新。
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
	 * 目标方块周围的 6 个面方向，顺序 = vanilla 的 {@code BlockBehaviour.UPDATE_SHAPE_ORDER}
	 * （BlockBehaviour.java:395：西、东、北、南、下、上）。它决定目标方块收到 6 次更新的顺序。
	 */
	static final Direction[] NEIGHBOUR_DIRECTIONS = {
			Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP
	};

	/**
	 * @param scanned             扫描过的坐标数（= 选定区域体积）
	 * @param airSkipped          因是空气而跳过的坐标数
	 * @param unloadedSkipped     因未加载 / 越界而跳过的坐标数
	 * @param shapeUpdates        实际执行的形状更新次数（每个非空气坐标最多 6 次）
	 * @param shapeUpdatesSkipped 因邻居所在位置未加载 / 越界而跳过的形状更新次数
	 * @param millis              执行耗时（毫秒）
	 */
	public record Result(long scanned, long airSkipped, long unloadedSkipped,
			long shapeUpdates, long shapeUpdatesSkipped, long millis) {
	}

	private static final class Counters {
		private long scanned;
		private long airSkipped;
		private long unloadedSkipped;
		private long shapeUpdates;
		private long shapeUpdatesSkipped;
	}

	public static Result apply(ServerLevel level, BoxRegion region) {
		return apply(level, region, level::isLoaded);
	}

	/**
	 * 包内可见的重载：把「该位置是否可用」抽成参数，好让无游戏自测用代理 {@link LevelAccessor}
	 * 驱动整段扫描逻辑（跳空气、6 个方向、计数、邻居未加载跳过），而不必构造 {@link ServerLevel}。
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

			for (Direction direction : NEIGHBOUR_DIRECTIONS) {
				BlockPos neighbourPos = pos.relative(direction);

				// 邻居可能落在选定区域之外、甚至未加载区块里：先守卫，绝不触发区块加载
				if (!loaded.test(neighbourPos)) {
					counters.shapeUpdatesSkipped++;
					continue;
				}

				applyShapeUpdateFrom(level, pos, direction);
				counters.shapeUpdates++;
			}
		});

		long millis = (System.nanoTime() - start) / 1_000_000L;
		return new Result(counters.scanned, counters.airSkipped, counters.unloadedSkipped,
				counters.shapeUpdates, counters.shapeUpdatesSkipped, millis);
	}

	/**
	 * 让 {@code target} 收到一次 PP 更新：即因为 {@code neighbourDirection} 方向的邻居「发生变化」而重算自己的形状。
	 *
	 * <p><b>参数方向必须照抄 vanilla 的调用约定</b>（{@code BlockBehaviour.BlockStateBase.updateNeighbourShapes}，
	 * BlockBehaviour.java:1074）：vanilla 的调用形式是
	 * {@code level.neighborShapeChanged(方向, 被更新的方块, 发生变化的方块, 状态, flags, limit)}，
	 * 其中方向 = {@code direction.getOpposite()}，也就是「从<b>被更新的方块</b>指向<b>发生变化的方块</b>的方向」。
	 * 这里的被更新方块就是 {@code target}，发生变化的方块是它 {@code neighbourDirection} 方向的邻居，
	 * 所以第一个参数直接传 {@code neighbourDirection}（<b>不要取反</b>）。
	 *
	 * <p>若误传反向：地板火把这类只在 {@code directionToNeighbour == DOWN} 时才检查附着的方块就不会掉落
	 * （BaseTorchBlock.java:28-30），游戏内验收会直接失败。自测里有一条"委托接口默认实现"的行为测试专门盯这一点。
	 */
	static void applyShapeUpdateFrom(LevelAccessor level, BlockPos target, Direction neighbourDirection) {
		BlockPos neighbourPos = target.relative(neighbourDirection);
		BlockState neighbourState = level.getBlockState(neighbourPos);
		level.neighborShapeChanged(neighbourDirection, target, neighbourPos, neighbourState,
				PP_UPDATE_FLAGS, PP_UPDATE_LIMIT);
	}
}
