package xyz.wagyourtail.unimined.minecraft.patch.remap

import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.Project
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.transform.reamp.MinecraftRemapper
import xyz.wagyourtail.unimined.minecraft.MinecraftProviderImpl
import xyz.wagyourtail.unimined.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.minecraft.patch.forge.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.util.consumerApply
import kotlin.io.path.*

@Suppress("MemberVisibilityCanBePrivate")
class MinecraftRemapperImpl(
    val project: Project,
    val provider: MinecraftProviderImpl,
) : MinecraftRemapper() {
    private val mappings by lazy { provider.parent.mappingsProvider }

    override var tinyRemapperConf: (TinyRemapper.Builder) -> Unit = {}

    val MC_LV_PATTERN = Regex("\\$\\$\\d+")

    val JSR_TO_JETBRAINS = mapOf(
        "javax/annotation/Nullable" to "org/jetbrains/annotations/Nullable",
        "javax/annotation/Nonnull" to "org/jetbrains/annotations/NotNull",
        "javax/annotation/concurrent/Immutable" to "org/jetbrains/annotations/Unmodifiable"
    )

    @ApiStatus.Internal
    fun provide(minecraft: MinecraftJar, remapTo: String, remapFallback: String): MinecraftJar {
        return minecraft.let(consumerApply {
            val mappingsId = mappings.getCombinedNames(minecraft.envType)
            val parent = if (mappings.hasStubs(envType)) {
                provider.parent.getLocalCache().resolve("minecraft").resolve(mappingsId).createDirectories()
            } else {
                parentPath.resolve(mappingsId)
            }
            val target = MinecraftJar(
                minecraft,
                parentPath = parent,
                mappingNamespace = remapTo,
                fallbackNamespace = remapFallback
            )



            if (target.path.exists() && !project.gradle.startParameter.isRefreshDependencies) {
                return@consumerApply target
            }

            val remapperB = TinyRemapper.newRemapper()
                .withMappings(
                    mappings.getMappingProvider(
                        envType,
                        mappingNamespace,
                        fallbackNamespace,
                        remapFallback,
                        remapTo
                    )
                )
                .withMappings { JSR_TO_JETBRAINS.forEach(it::acceptClass) }
                .threads(Runtime.getRuntime().availableProcessors())
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .invalidLvNamePattern(MC_LV_PATTERN.toPattern())
                .inferNameFromSameLvIndex(true)
                .checkPackageAccess(true)
                .fixPackageAccess(true)
            tinyRemapperConf(remapperB)
            val remapper = remapperB.build()

            project.logger.lifecycle("Remapping minecraft to $remapTo")
            project.logger.info("Remapping ${path.name} to $target")
            project.logger.info("Mappings: $mappingNamespace to $remapTo")
            project.logger.info("Fallback: $fallbackNamespace to $remapFallback")

            try {
                remapper.readInputs(path)
                OutputConsumerPath.Builder(target.path).build().use {
                    it.addNonClassFiles(
                        path, remapper,
                        listOf(AccessTransformerMinecraftTransformer.atRemapper()) + NonClassCopyMode.FIX_META_INF.remappers
                    )
                    remapper.apply(it)
                }
            } catch (e: RuntimeException) {
                project.logger.warn("Failed to remap ${path.name} to $target")
                target.path.deleteIfExists()
                throw e
            }
            remapper.finish()
            target
        })
    }
}