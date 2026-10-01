package com.dshwork.updatecmd;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * mod 入口：Fabric 侧唯一的胶水代码。
 *
 * <p>指令本身的实现放在 {@link UpdateCommand}，那里不引用任何 Fabric API，
 * 因此可以在没有游戏环境的情况下用真实 Brigadier 直接驱动测试。
 */
public class UpdateCmd implements ModInitializer {
	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> UpdateCommand.register(dispatcher));
	}
}
