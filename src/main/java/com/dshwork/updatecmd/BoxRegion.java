package com.dshwork.updatecmd;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;

/**
 * 由两个端点确定的长方体区域（闭区间，含两端）。
 *
 * <p>{@code nc} 与 {@code pp} 两种模式共用：它只负责「区域是什么」与「按什么顺序遍历」，
 * 不关心每个坐标上做什么。
 *
 * <p>端点顺序无关：{@code (a, b)} 与 {@code (b, a)} 得到同一个区域。
 * 负坐标、单点区域（两据点相同）都按同一套规则处理。
 */
public final class BoxRegion {
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;

	private BoxRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
	}

	public static BoxRegion of(BlockPos a, BlockPos b) {
		return new BoxRegion(
				Math.min(a.getX(), b.getX()),
				Math.min(a.getY(), b.getY()),
				Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()),
				Math.max(a.getY(), b.getY()),
				Math.max(a.getZ(), b.getZ()));
	}

	/**
	 * 区域内坐标数量。
	 *
	 * @throws ArithmeticException 数量超出 64 位可表示范围（坐标范围极端时才会发生）
	 */
	public long volume() {
		long sizeX = (long) this.maxX - this.minX + 1L;
		long sizeY = (long) this.maxY - this.minY + 1L;
		long sizeZ = (long) this.maxZ - this.minZ + 1L;
		return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
	}

	/**
	 * 按约定顺序遍历区域内每一个坐标：<b>Y 低→高 → X 小→大 → Z 小→大</b>。
	 *
	 * <p>循环变量用 long，避免坐标取到 {@link Integer#MAX_VALUE} 时自增溢出导致死循环。
	 */
	public void forEachPos(Consumer<BlockPos> visitor) {
		for (long y = this.minY; y <= (long) this.maxY; y++) {
			for (long x = this.minX; x <= (long) this.maxX; x++) {
				for (long z = this.minZ; z <= (long) this.maxZ; z++) {
					visitor.accept(new BlockPos((int) x, (int) y, (int) z));
				}
			}
		}
	}
}
