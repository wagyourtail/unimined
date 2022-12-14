package xyz.wagyourtail.unimined.minecraft.patch

import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.transform.patch.MinecraftPatcher
import xyz.wagyourtail.unimined.api.run.RunConfig
import xyz.wagyourtail.unimined.minecraft.MinecraftProviderImpl
import java.nio.file.Path

abstract class AbstractMinecraftTransformer protected constructor(
    protected val project: Project,
    val provider: MinecraftProviderImpl
) : MinecraftPatcher {
    @ApiStatus.Internal
    open fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        //TODO: do this for real
        return clientjar
    }

    @ApiStatus.Internal
    abstract fun transform(minecraft: MinecraftJar): MinecraftJar

    private fun applyRunConfigs(tasks: TaskContainer) {
        if (provider.runs.off) return
        project.logger.lifecycle("Applying run configs")
        project.logger.info("client: ${provider.client}, server: ${provider.server}")
        if (provider.minecraft.client) {
            project.logger.info("client config")
            applyClientRunConfig(tasks, provider.runs.client)
        }
        if (provider.minecraft.server) {
            project.logger.info("server config")
            applyServerRunConfig(tasks, provider.runs.server)
        }
    }

    @ApiStatus.Internal
    open fun applyClientRunConfig(tasks: TaskContainer, action: (RunConfig) -> Unit = {}) {
        provider.provideVanillaRunClientTask(tasks, action)
    }

    @ApiStatus.Internal
    open fun applyServerRunConfig(tasks: TaskContainer, action: (RunConfig) -> Unit = {}) {
        provider.provideVanillaRunServerTask(tasks, action)
    }

    @ApiStatus.Internal
    open fun afterEvaluate() {
        provider.parent.events.register(::sourceSets)
        provider.parent.events.register(::applyRunConfigs)
    }

    @ApiStatus.Internal
    open fun sourceSets(sourceSets: SourceSetContainer) {
    }

    @ApiStatus.Internal
    open fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        return baseMinecraft
    }

    @ApiStatus.Internal
    open fun afterRemapJarTask(output: Path) {
        // do nothing
    }
}