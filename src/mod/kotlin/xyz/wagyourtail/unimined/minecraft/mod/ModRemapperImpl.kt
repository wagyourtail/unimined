package xyz.wagyourtail.unimined.minecraft.mod

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loom.util.kotlin.KotlinClasspathService
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader
import net.fabricmc.tinyremapper.*
import net.fabricmc.tinyremapper.OutputConsumerPath.ResourceRemapper
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.configurationcache.extensions.capitalized
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.mod.ModRemapper
import xyz.wagyourtail.unimined.minecraft.patch.fabric.AccessWidenerMinecraftTransformer
import xyz.wagyourtail.unimined.util.LazyMutable
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

class ModRemapperImpl(
    val project: Project,
    provider: ModProviderImpl
) : ModRemapper(provider) {

    val mcProvider by lazy { provider.parent.minecraftProvider }
    val mappings by lazy { provider.parent.mappingsProvider }

    override var tinyRemapperConf by LazyMutable { provider.parent.minecraftProvider.mcRemapper.tinyRemapperConf }

    private val combinedConfig = Configs(project, EnvType.COMBINED, this)
    private val clientConfig = Configs(project, EnvType.CLIENT, this)
    private val serverConfig = Configs(project, EnvType.SERVER, this)


    private val internalCombinedModRemapperConfiguration: Configuration = project.configurations.maybeCreate("internalModRemapper")
        .apply {
            exclude(
                mapOf(
                    "group" to "net.fabricmc",
                    "module" to "fabric-loader"
                )
            )
        }

    @ApiStatus.Internal
    override fun internalModRemapperConfiguration(envType: EnvType): Configuration = when (envType) {
        EnvType.COMBINED -> internalCombinedModRemapperConfiguration
        EnvType.CLIENT -> project.configurations.maybeCreate("internalModRemapperClient").apply {
            try {
                extendsFrom(internalCombinedModRemapperConfiguration)
            } catch (ignored: InvalidUserDataException) {
            }
        }

        EnvType.SERVER -> project.configurations.maybeCreate("internalModRemapperServer").apply {
            try {
                extendsFrom(internalCombinedModRemapperConfiguration)
            } catch (ignored: InvalidUserDataException) {
            }
        }
    }

    init {
        project.repositories.forEach { repo ->
            repo.content {
                it.excludeGroupByRegex("remapped_.+")
            }
        }
        project.repositories.flatDir { repo ->
            repo.dirs(modTransformFolder().toAbsolutePath().toString())
            repo.content {
                it.includeGroupByRegex("remapped_.+")
            }
        }
    }

    private val seen = mutableSetOf<EnvType>()

    fun remap(envType: EnvType) {
        val configs = when (envType) {
            EnvType.COMBINED -> combinedConfig
            EnvType.CLIENT -> clientConfig
            EnvType.SERVER -> serverConfig
        }
        if (seen.contains(envType)) return
        seen.add(envType)
        val count = configs.configurations.sumOf {
            preTransform(configs.envType, it)
        }
        if (count == 0) return
        val tr = TinyRemapper.newRemapper()
            .withMappings(
                mappings.getMappingProvider(
                    configs.envType,
                    fromNamespace,
                    fromFallbackNamespace,
                    toFallbackNamespace,
                    toNamespace
                )
            )
            .renameInvalidLocals(true)
            .inferNameFromSameLvIndex(true)
            .threads(Runtime.getRuntime().availableProcessors())
            .rebuildSourceFilenames(true)

        tr.extension(MixinExtension())
        val classpath = KotlinClasspathService.getOrCreateIfRequired(project)
        if (classpath != null) {
            tr.extension(KotlinRemapperClassloader.create(classpath).tinyRemapperExtension)
        }

        tinyRemapperConf(tr)
        tinyRemapper = tr.build()
        val mc = provider.parent.minecraftProvider.getMinecraftWithMapping(configs.envType, fromNamespace, fromFallbackNamespace)
        tinyRemapper.readClassPathAsync(mc)
        project.logger.lifecycle("Remapping mods")
        project.logger.info("using mc $mc")
        project.logger.info("using mappings $fromNamespace -> $toNamespace")
        project.logger.info("using fallback mappings $fromFallbackNamespace -> $toFallbackNamespace")
        tinyRemapper.readClassPathAsync(*mcProvider.mcLibraries.resolve().map { it.toPath() }.toTypedArray())
        configs.configurations.forEach {
            transform(configs.envType, it)
        }
        configs.configurations.forEach {
            postTransform(configs.envType, it)
        }
        tinyRemapper.finish()
    }

    private val dependencyMap = mutableMapOf<Configuration, MutableSet<Dependency>>()

    private fun preTransform(envType: EnvType, configuration: Configuration): Int {
        val count = configuration.dependencies.size
        configuration.dependencies.forEach {
            internalModRemapperConfiguration(envType).dependencies.add(it)
            dependencyMap.computeIfAbsent(configuration) { mutableSetOf() } += (it)
        }
        configuration.dependencies.clear()
        return count
    }

    private fun transform(envType: EnvType, configuration: Configuration) {
        dependencyMap[configuration]?.forEach {
            transformMod(envType, it)
        }
    }

    private fun postTransform(envType: EnvType, configuration: Configuration) {
        dependencyMap[configuration]?.forEach {

            getOutputs(envType, it).forEach { artifact ->
                configuration.dependencies.add(
                    project.dependencies.create(
                        artifact
                    )
                )
            }
        }
    }

    private fun modTransformFolder(): Path {
        return provider.parent.getLocalCache().resolve("modTransform").createDirectories()

    }

    private lateinit var tinyRemapper: TinyRemapper
    private val outputMap = mutableMapOf<File, InputTag>()

    private fun transformMod(envType: EnvType, dependency: Dependency) {
        val files = internalModRemapperConfiguration(envType).files(dependency)
        for (file in files) {
            if (file.extension == "jar") {
                val targetTag = tinyRemapper.createInputTag()
                tinyRemapper.readInputs(targetTag, file.toPath())
                outputMap[file] = targetTag
            }
        }
    }

    private fun getOutputs(envType: EnvType, dependency: Dependency): Set<String> {
        val combinedNames = mappings.getCombinedNames(envType)
        val outputs = mutableSetOf<String>()
        for (innerDep in internalModRemapperConfiguration(envType).resolvedConfiguration.getFirstLevelModuleDependencies { it == dependency }) {
            for (artifact in innerDep.allModuleArtifacts) {
                if (artifact.file.extension == "jar") {
                    val target = modTransformFolder().resolve("${artifact.file.nameWithoutExtension}-mapped-${combinedNames}-${mcProvider.mcPatcher.devNamespace}.${artifact.file.extension}")
                    val classifier = artifact.classifier?.let { "$it-" } ?: ""
                    outputs += "remapped_${artifact.moduleVersion.id.group}:${artifact.name}:${artifact.moduleVersion.id.version}:${classifier}mapped-${combinedNames}-${mcProvider.mcPatcher.devNamespace}"
                    if (target.exists()) {
                        continue
                    }
                    OutputConsumerPath.Builder(target).build().use {
                        it.addNonClassFiles(
                            artifact.file.toPath(),
                            tinyRemapper,
                            listOf(
                                AccessWidenerMinecraftTransformer.awRemapper(
                                    fromNamespace,
                                    toNamespace
                                ), innerJarStripper
                            ) + NonClassCopyMode.FIX_META_INF.remappers
                        )
                        tinyRemapper.apply(it, outputMap[artifact.file])
                    }
                } else {
                    outputs += artifact.id.toString()
                }
            }
        }
        return outputs
    }

    private val innerJarStripper: ResourceRemapper = object : ResourceRemapper {
        override fun canTransform(remapper: TinyRemapper, relativePath: Path): Boolean {
            return relativePath.name.contains(".mod.json")
        }

        override fun transform(
            destinationDirectory: Path,
            relativePath: Path,
            input: InputStream,
            remapper: TinyRemapper
        ) {
            val output = destinationDirectory.resolve(relativePath)
            output.parent.createDirectories()
            BufferedReader(InputStreamReader(input)).use { reader ->
                val json = JsonParser.parseReader(reader)
                json.asJsonObject.remove("jars")
                json.asJsonObject.remove("quilt_loader")
                BufferedWriter(
                    OutputStreamWriter(
                        BufferedOutputStream(
                            Files.newOutputStream(
                                output,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING
                            )
                        )
                    )
                ).use { writer ->
                    GsonBuilder().setPrettyPrinting().create().toJson(json, writer)
                }
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    data class Configs(val project: Project, val envType: EnvType, val parent: ModRemapperImpl) {
        val configurations = mutableSetOf<Configuration>()
        private val envTypeName = envType.classifier?.capitalized() ?: ""

        private fun registerConfiguration(configuration: Configuration): Configuration {
            configurations += configuration
            return configuration
        }

        val modCompileOnly: Configuration = registerConfiguration(
            project.configurations.maybeCreate("modCompileOnly$envTypeName")
                .apply {
                    extendsFrom(project.configurations.getByName("compileOnly"))
                    exclude(
                        mapOf(
                            "group" to "net.fabricmc",
                            "module" to "fabric-loader"
                        )
                    )
                })

        val modRuntimeOnly: Configuration = registerConfiguration(
            project.configurations.maybeCreate("modRuntimeOnly$envTypeName")
                .apply {
                    extendsFrom(project.configurations.getByName("runtimeOnly"))
                    exclude(
                        mapOf(
                            "group" to "net.fabricmc",
                            "module" to "fabric-loader"
                        )
                    )
                })

        val localRuntime: Configuration = project.configurations.maybeCreate("localRuntime$envTypeName").apply {
            extendsFrom(project.configurations.getByName("runtimeOnly"))
            exclude(
                mapOf(
                    "group" to "net.fabricmc",
                    "module" to "fabric-loader"
                )
            )
        }

        val modLocalRuntime: Configuration = registerConfiguration(
            project.configurations.maybeCreate("modLocalRuntime" + envTypeName)
                .apply {
                    extendsFrom(project.configurations.getByName("localRuntime"))
                    exclude(
                        mapOf(
                            "group" to "net.fabricmc",
                            "module" to "fabric-loader"
                        )
                    )
                })

        val modImplementation: Configuration = registerConfiguration(
            project.configurations.maybeCreate("modImplementation" + envTypeName)
                .apply {
                    extendsFrom(project.configurations.getByName("implementation"))
                    exclude(
                        mapOf(
                            "group" to "net.fabricmc",
                            "module" to "fabric-loader"
                        )
                    )
                })


        init {
            parent.provider.parent.events.register(::sourceSets)
        }

        private fun sourceSets(sourceSets: SourceSetContainer) {
            when (envType) {
                EnvType.SERVER -> {
                    for (sourceSet in parent.provider.parent.minecraftProvider.serverSourceSets){
                        sourceSet.compileClasspath += modCompileOnly + modImplementation
                        sourceSet.runtimeClasspath += localRuntime + modRuntimeOnly + modLocalRuntime + modImplementation
                    }
                }

                EnvType.CLIENT -> {
                    for (sourceSet in parent.provider.parent.minecraftProvider.clientSourceSets){
                        sourceSet.compileClasspath += modCompileOnly + modImplementation
                        sourceSet.runtimeClasspath += localRuntime + modRuntimeOnly + modLocalRuntime + modImplementation
                    }
                }

                EnvType.COMBINED -> {
                    for (sourceSet in parent.provider.parent.minecraftProvider.combinedSourceSets){
                        sourceSet.compileClasspath += modCompileOnly + modImplementation
                        sourceSet.runtimeClasspath += localRuntime + modRuntimeOnly + modLocalRuntime + modImplementation
                    }
                    for (sourceSet in parent.provider.parent.minecraftProvider.serverSourceSets){
                        sourceSet.compileClasspath += modCompileOnly + modImplementation
                        sourceSet.runtimeClasspath += localRuntime + modRuntimeOnly + modLocalRuntime + modImplementation
                    }
                    for (sourceSet in parent.provider.parent.minecraftProvider.clientSourceSets){
                        sourceSet.compileClasspath += modCompileOnly + modImplementation
                        sourceSet.runtimeClasspath += localRuntime + modRuntimeOnly + modLocalRuntime + modImplementation
                    }
                }
            }
        }
    }
}
