package com.dshwork.updatecmd;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /update} 指令。
 *
 * <pre>
 * /update nc &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *     区域内每个坐标「收到」一次 NC 更新（语义 B）。
 *
 * /update pp &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;x2&gt; &lt;y2&gt; &lt;z2&gt;
 *     扫描区域：空气坐标跳过；非空气坐标则把它的 6 个面邻居各当作更新核做一次 PP 更新。
 * </pre>
 *
 * 两个坐标确定一个长方体（闭区间，含两端，顺序无关）。
 */
public final class UpdateCommand {
	private UpdateCommand() {
	}

	private static final SimpleCommandExceptionType ERROR_VOLUME_OVERFLOW = new SimpleCommandExceptionType(
			Component.literal("坐标范围过大：坐标数量超出 64 位可计数上限，已中止"));

	/**
	 * 把指令树注册进分发器。
	 *
	 * <p>独立成静态方法是有意为之：无游戏环境的自测会直接调用它，
	 * 用真实 Brigadier 解析指令并断言参数、权限与树结构。
	 */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("update")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("nc")
						.then(Commands.argument("pos1", BlockPosArgument.blockPos())
								.then(Commands.argument("pos2", BlockPosArgument.blockPos())
										.executes(context -> runNc(
												context.getSource(),
												BlockPosArgument.getBlockPos(context, "pos1"),
												BlockPosArgument.getBlockPos(context, "pos2"))))))
				.then(Commands.literal("pp")
						.then(Commands.argument("pos1", BlockPosArgument.blockPos())
								.then(Commands.argument("pos2", BlockPosArgument.blockPos())
										.executes(context -> runPp(
												context.getSource(),
												BlockPosArgument.getBlockPos(context, "pos1"),
												BlockPosArgument.getBlockPos(context, "pos2")))))));
	}

	private static int runNc(CommandSourceStack source, BlockPos a, BlockPos b) throws CommandSyntaxException {
		BoxRegion region = BoxRegion.of(a, b);
		long volume = volumeOf(region);

		ServerLevel level = source.getLevel();
		NcUpdater.Result result = NcUpdater.apply(level, region);

		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"已对 %d 个坐标执行 nc 更新（区域总量 %d，跳过 %d 个未加载/越界坐标，耗时 %d ms）",
				result.updated(), volume, result.skipped(), result.millis())), true);

		return (int) Math.min(result.updated(), Integer.MAX_VALUE);
	}

	private static int runPp(CommandSourceStack source, BlockPos a, BlockPos b) throws CommandSyntaxException {
		BoxRegion region = BoxRegion.of(a, b);
		volumeOf(region); // 极端坐标范围提前报错，避免白跑一趟

		ServerLevel level = source.getLevel();
		PpUpdater.Result result = PpUpdater.apply(level, region);

		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"已执行 pp 更新：扫描 %d 个坐标（跳过空气 %d、未加载/越界 %d），"
						+ "让目标方块收到 %d 次形状更新（邻居未加载跳过 %d 次），耗时 %d ms",
				result.scanned(), result.airSkipped(), result.unloadedSkipped(),
				result.shapeUpdates(), result.shapeUpdatesSkipped(), result.millis())), true);

		return (int) Math.min(result.shapeUpdates(), Integer.MAX_VALUE);
	}

	private static long volumeOf(BoxRegion region) throws CommandSyntaxException {
		try {
			return region.volume();
		} catch (ArithmeticException e) {
			// 仅当坐标范围达到天文数字（> 2^63 个坐标）时才会发生，属于算术完整性检查，不是体积上限策略
			throw ERROR_VOLUME_OVERFLOW.create();
		}
	}
}
