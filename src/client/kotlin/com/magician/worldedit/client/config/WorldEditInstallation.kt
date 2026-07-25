package com.magician.worldedit.client.config

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants

data class WorldEditInstallation(
	val installed: Boolean,
	val version: String? = null,
	val minecraftVersion: String = SharedConstants.getCurrentVersion().name(),
)

object WorldEditInstallationChecker {
	@Volatile
	private var installation: WorldEditInstallation = findInstallation()

	fun checkAtStartup() {
		installation = findInstallation()
	}

	fun current(): WorldEditInstallation = installation

	private fun findInstallation(): WorldEditInstallation {
		val mod = FabricLoader.getInstance().getModContainer("worldedit").orElse(null)
		return if (mod == null) {
			WorldEditInstallation(installed = false)
		} else {
			WorldEditInstallation(installed = true, version = mod.metadata.version.friendlyString)
		}
	}
}
