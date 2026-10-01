package com.dshwork.updatecmd;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * nc 更新执行器。
 *
 * <p>语义（已与使用者确认）：<b>让该坐标自身的方块「收到」一次 NC 更新</b>，
 * 而不是以该坐标为更新核向四周扩散。区域内每个坐标恰好触发一次，
 * 区域外的方块不会被本指令直接更新。
 *
 * <p>实现链条（依据 26.2 反编译源码，非记忆）：
 * <ol>
 *   <li>{@code ServerLevel.neighborChanged(BlockPos, Block, Orientation)} —— ServerLevel.java:1123</li>
 *   <li>{@code CollectingNeighborUpdater.SimpleNeighborUpdate} —— CollectingNeighborUpdater.java:99-104
 *       （取该坐标当前 BlockState，再走 executeUpdate）</li>
 *   <li>{@code NeighborUpdater.executeUpdate} → {@code state.handleNeighborChanged(...)} —— NeighborUpdater.java:53-55</li>
 * </ol>
 *
 * <p>{@code changedBlock} 传 {@link Blocks#AIR} 的理由：vanilla 在「方块被移除」后通知邻居时，
 * 传的就是该位置的新方块（也就是空气）——{@code ServerLevel.updateNeighboursOnBlockSet}
 * （ServerLevel.java:818-826，{@code this.updateNeighborsAt(pos, blockState.getBlock())}）。
 * 另外，红石线在「实验性红石求值器」开启时会跳过 {@code block == this} 的更新
 * （RedStoneWireBlock.java:288-290），传空气可避开这一自过滤。
 *
 * <p><b>未加载区块的处理</b>：一律跳过，绝不加载。原因：{@code Level.getBlockState} 对未加载区块
 * 会走 {@code ServerChunkCache.getChunk(..., loadOrGenerate=true)} 并 {@code managedBlock} 阻塞等待，
 * 即同步加载/生成区块（ServerChunkCache.java:136-143、LevelReader.java:121-127）。
 * 这里用 {@code Level.isLoaded}（Level.java:501，内部只查 {@code getChunkSource().hasChunk}）做守卫。
 */
public final class NcUpdater {
	private NcUpdater() {
	}

	/**
	 * @param updated 真正执行了 NC 更新的坐标数
	 * @param skipped 因未加载 / 超出世界高度而被跳过的坐标数
	 * @param millis  执行耗时（毫秒）
	 */
	public record Result(long updated, long skipped, long millis) {
	}

	private static final class Counters {
		private long updated;
		private long skipped;
	}

	public static Result apply(ServerLevel level, BoxRegion region) {
		Counters counters = new Counters();
		long start = System.nanoTime();

		// 用 BoxRegion.forEachPos 而不是自己写循环：遍历顺序是自测断言过的那个契约，
		// 生产代码与测试共用同一份实现，改一处不会漏掉另一处。
		region.forEachPos(pos -> {
			if (!level.isLoaded(pos)) {
				counters.skipped++;
				return;
			}

			// 必须传不可变的 BlockPos：CollectingNeighborUpdater.SimpleNeighborUpdate 直接持有传入的
			// pos 引用（未做 immutable 拷贝），若传会被复用的可变对象，被排队的连锁更新会读到错误坐标。
			level.neighborChanged(pos, Blocks.AIR, null);
			counters.updated++;
		});

		long millis = (System.nanoTime() - start) / 1_000_000L;
		return new Result(counters.updated, counters.skipped, millis);
	}
}
