package xyz.wagyourtail.unimined.providers.minecraft.patch.remap

import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.maybeCreate
import xyz.wagyourtail.unimined.providers.minecraft.EnvType
import xyz.wagyourtail.unimined.providers.minecraft.MinecraftProvider
import java.nio.file.Path
import kotlin.io.path.*

@Suppress("MemberVisibilityCanBePrivate")
class MinecraftRemapper(
    val project: Project,
    val provider: MinecraftProvider,
) {
    private val mappings by lazy { provider.parent.mappingsProvider }

    var remapFrom = "official"
    var fallbackTarget = "intermediary"
    var fallbackFrom = "official"
    var tinyRemapperConf: (TinyRemapper.Builder) -> Unit = {}

    @ApiStatus.Internal
    fun provide(envType: EnvType, file: Path, remapTo: String, remapFrom: String = this.remapFrom, skipMappingId: Boolean = false): Path {
        val parent = if (mappings.hasStubs(envType)) {
            provider.parent.getLocalCache().resolve("minecraft").maybeCreate()
        } else {
            file.parent
        }
        val target = if (skipMappingId) {
            parent.resolve(mappings.getCombinedNames(envType))
                .resolve("${file.nameWithoutExtension}-${remapTo}.${file.extension}")
        } else {
            parent.resolve(mappings.getCombinedNames(envType))
                .resolve("${file.nameWithoutExtension}-mapped-${mappings.getCombinedNames(envType)}-${remapTo}.${file.extension}")
        }



        if (target.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            return target
        }

        val remapperB = TinyRemapper.newRemapper()
            .withMappings(mappings.getMappingProvider(envType, remapFrom, fallbackFrom, fallbackTarget, remapTo))
            .renameInvalidLocals(true)
            .inferNameFromSameLvIndex(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .checkPackageAccess(true)
            .fixPackageAccess(true)
            .rebuildSourceFilenames(true)
        tinyRemapperConf(remapperB)
        val remapper = remapperB.build()

        project.logger.warn("Remapping ${file.name} to $target")

        try {
            OutputConsumerPath.Builder(target).build().use {
                it.addNonClassFiles(file, NonClassCopyMode.FIX_META_INF, null)
                remapper.readInputs(file)
                remapper.apply(it)
            }
        } catch (e: RuntimeException) {
            project.logger.warn("Failed to remap ${file.name} to $target")
            throw e
        }
        remapper.finish()
        return target
    }
}