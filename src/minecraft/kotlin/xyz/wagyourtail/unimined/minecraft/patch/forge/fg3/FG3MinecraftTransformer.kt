package xyz.wagyourtail.unimined.minecraft.patch.forge.fg3

import com.google.gson.JsonParser
import net.fabricmc.mappingio.format.ZipReader
import net.minecraftforge.binarypatcher.ConsoleTool
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.*
import xyz.wagyourtail.unimined.api.Constants
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.run.RunConfig
import xyz.wagyourtail.unimined.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.minecraft.patch.forge.ForgeMinecraftTransformer
import xyz.wagyourtail.unimined.minecraft.patch.forge.fg3.mcpconfig.McpConfigData
import xyz.wagyourtail.unimined.minecraft.patch.forge.fg3.mcpconfig.McpConfigStep
import xyz.wagyourtail.unimined.minecraft.patch.forge.fg3.mcpconfig.McpExecutor
import xyz.wagyourtail.unimined.minecraft.patch.jarmod.JarModMinecraftTransformer
import xyz.wagyourtail.unimined.util.getFile
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.*
import kotlin.io.path.*

class  FG3MinecraftTransformer(project: Project, val parent: ForgeMinecraftTransformer) : JarModMinecraftTransformer(
    project, parent.provider, Constants.FORGE_PROVIDER
) {

    override val prodNamespace: String = "searge"

    override var devNamespace: String
        get() = parent.devNamespace
        set(value) {
            parent.devNamespace = value
        }

    override var devFallbackNamespace: String
        get() = parent.devFallbackNamespace
        set(value) {
            parent.devFallbackNamespace = value
        }

    @ApiStatus.Internal
    val forgeUd = project.configurations.detachedConfiguration()

    @ApiStatus.Internal
    val clientExtra = project.configurations.maybeCreate(Constants.FORGE_CLIENT_EXTRA)

    lateinit var mcpConfig: Dependency
    val mcpConfigData by lazy {
        val config = provider.parent.mappingsProvider.getMappings(EnvType.COMBINED).getFile(mcpConfig, Regex("zip"))
        val configJson = ZipReader.readInputStreamFor("config.json", config.toPath()) {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }
        McpConfigData.fromJson(configJson)
    }

    override fun afterEvaluate() {
        val forgeDep = parent.forge.dependencies.last()

        // detect if userdev3 or userdev
        //   read if forgeDep has binpatches file
        val forgeUni = parent.forge.getFile(forgeDep)
        val userdevClassifier = ZipReader.readInputStreamFor<String?>(
            "binpatches.pack.lzma", forgeUni.toPath(), false
        ) {
            "userdev3"
        } ?: "userdev"

        val userdev = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:$userdevClassifier"
        forgeUd.dependencies.add(project.dependencies.create(userdev))

//        val installer = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:installer"
//        forgeInstaller.dependencies.add(project.dependencies.create(installer))

        provider.parent.mappingsProvider.getMappings(EnvType.COMBINED).dependencies.apply {
            val empty = isEmpty()
            mcpConfig = project.dependencies.create("de.oceanlabs.mcp:mcp_config:${provider.minecraft.version}@zip")
            add(mcpConfig)
            if (empty) {
                if (parent.mcpVersion == null || parent.mcpChannel == null) throw IllegalStateException("mcpVersion and mcpChannel must be set in forge block for 1.7+")
                add(project.dependencies.create("de.oceanlabs.mcp:mcp_${parent.mcpChannel}:${parent.mcpVersion}@zip"))
            } else {
                val deps = this.filter { it != mcpConfig }
                clear()
                add(mcpConfig)
                addAll(deps)
            }
        }

        for (element in userdevCfg.get("libraries")?.asJsonArray ?: listOf()) {
            if (element.asString.contains("legacydev")) continue
            val dep = element.asString.split(":")
            if (dep[1] == "gson" && dep[2] == "2.8.0") {
                provider.mcLibraries.dependencies.add(project.dependencies.create("${dep[0]}:${dep[1]}:2.8.9"))
            } else {
                provider.mcLibraries.dependencies.add(project.dependencies.create(element.asString.replace(".+", "")))
            }
        }

        // extract and provide /fmlloadertargets/fmlloadertargets.jar
        val loaderTargets = provider.parent.getGlobalCache().resolve("fmlloadertargets.jar")
        FG3MinecraftTransformer::class.java.getResourceAsStream("/fmlloadertargets/fmlloadertargets.jar").use { input ->
            Files.copy(
                input,
                loaderTargets,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        provider.mcLibraries.dependencies.add(project.dependencies.create(project.files(loaderTargets.toFile())))


        // get forge userdev jar
        val forgeUd = forgeUd.getFile(forgeUd.dependencies.last())
        if (userdevCfg.has("inject")) {
            project.logger.lifecycle("injecting forge userdev into minecraft jar")
            this.addTransform { outputJar ->
                ZipReader.openZipFileSystem(forgeUd.toPath()).use { inputJar ->
                    val inject = inputJar.getPath("/" + userdevCfg.get("inject").asString)
                    if (Files.exists(inject)) {
                        project.logger.info("injecting forge userdev into minecraft jar")
                        Files.walk(inject).forEach { path ->
                            project.logger.debug("testing $path")
                            if (!Files.isDirectory(path)) {
                                val target = outputJar.getPath("/${path.relativeTo(inject)}")
                                project.logger.debug("injecting $path into minecraft jar")
                                Files.createDirectories(target.parent)
                                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            }
        }

        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = get("main").asString
            if (!mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("inserting mcp mappings")
                provider.mcLibraries.dependencies.add(
                    project.dependencies.create(project.files(parent.srgToMCPAsMCP))
                )
            }
        }

        addTransform {
            it.getPath("")
        }
    }

    val userdevCfg by lazy {
        // get forge userdev jar
        val forgeUd = forgeUd.getFile(forgeUd.dependencies.last())

        ZipReader.readInputStreamFor("config.json", forgeUd.toPath()) {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }!!
    }

    @Throws(IOException::class)
    private fun executeMcp(step: String, outputPath: Path, envType: EnvType) {
        val type = when (envType) {
            EnvType.CLIENT -> "client"
            EnvType.SERVER -> "server"
            EnvType.COMBINED -> "joined"
        }
        val steps: List<McpConfigStep> = mcpConfigData.steps[type]!!
        val executor = McpExecutor(
            project,
            provider,
            outputPath.parent.resolve("mcpconfig").createDirectories(),
            steps,
            mcpConfigData.functions
        )
        val output: Path = executor.executeUpTo(step)
        Files.copy(output, outputPath, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun sourceSets(sourceSets: SourceSetContainer) {

        for (sourceSet in provider.combinedSourceSets) {
            sourceSet.compileClasspath += clientExtra
            sourceSet.runtimeClasspath += clientExtra
        }

        for (sourceSet in provider.clientSourceSets) {
            sourceSet.compileClasspath += clientExtra
            sourceSet.runtimeClasspath += clientExtra
        }

        for (sourceSet in provider.serverSourceSets) {
            sourceSet.compileClasspath += clientExtra
            sourceSet.runtimeClasspath += clientExtra
        }

    }

    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("Merging client and server jars...")
        val output = MinecraftJar(
            clientjar,
            parentPath = provider.minecraft.mcVersionFolder(provider.minecraft.version)
                .resolve("forge"),
            envType = EnvType.COMBINED,
            mappingNamespace = if (userdevCfg["notchObf"]?.asBoolean == true) "official" else "searge"
        )
        createClientExtra(clientjar, serverjar, output.path)
        if (output.path.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            return output
        }
        if (userdevCfg["notchObf"]?.asBoolean == true) {
            executeMcp("merge", output.path, EnvType.COMBINED)
        } else {
            executeMcp("rename", output.path, EnvType.COMBINED)
        }
        return output
    }

    private fun createClientExtra(
        baseMinecraftClient: MinecraftJar, baseMinecraftServer: MinecraftJar, patchedMinecraft: Path
    ) {
        val clientExtra = patchedMinecraft.parent.createDirectories()
            .resolve("client-extra-${provider.minecraft.version}.jar")

        if (this.clientExtra.dependencies.isEmpty()) {
            this.clientExtra.dependencies.add(
                project.dependencies.create(
                    project.files(clientExtra.toString())
                )
            )
        }

        if (clientExtra.exists()) {
            if (project.gradle.startParameter.isRefreshDependencies) {
                clientExtra.deleteExisting()
            } else {
                return
            }
        }

        ZipReader.openZipFileSystem(clientExtra, mapOf("mutable" to true, "create" to true)).use { extra ->
            ZipReader.openZipFileSystem(baseMinecraftClient.path).use { base ->
                for (path in Files.walk(base.getPath("/"))) {
                    // skip meta-inf
                    if (path.nameCount > 0 && path.getName(0).toString().equals("META-INF", ignoreCase = true)) continue
//            project.logger.warn("Checking $path")
                    if (!path.isDirectory() && path.extension != "class") {
//                project.logger.warn("Copying $path")
                        val target = extra.getPath(path.toString())
                        target.parent.createDirectories()
                        path.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        project.logger.lifecycle("transforming minecraft jar for FG3")
        project.logger.info("minecraft: $minecraft")
        val forgeUniversal = parent.forge.dependencies.last()
        val forgeUd = forgeUd.getFile(forgeUd.dependencies.last())

        val outFolder = minecraft.path.parent.resolve("${forgeUniversal.name}-${forgeUniversal.version}")
            .createDirectories()

        val patchedMC = MinecraftJar(
            minecraft,
            name = "forge",
            parentPath = outFolder
        )

        // if userdev cfg says notch
        if (userdevCfg["notchObf"]?.asBoolean == true && minecraft.envType != EnvType.COMBINED) {
            throw IllegalStateException("Forge userdev3 (legacy fg3, aka 1.12.2) is not supported for non-combined environments.")
        }

        //  extract binpatches
        val binPatchFile = ZipReader.readInputStreamFor(userdevCfg["binpatches"].asString, forgeUd.toPath()) {
            outFolder.resolve("binpatches.pack.lzma").apply {
                writeBytes(
                    it.readBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
                )
            }
        }

        if (!patchedMC.path.exists() || project.gradle.startParameter.isRefreshDependencies) {
            patchedMC.path.deleteIfExists()
            val args = (userdevCfg["binpatcher"].asJsonObject["args"].asJsonArray.map {
                when (it.asString) {
                    "{clean}" -> minecraft.path.toString()
                    "{patch}" -> binPatchFile.toString()
                    "{output}" -> patchedMC.path.toString()
                    else -> it.asString
                }
            } + listOf("--data", "--unpatched")).toTypedArray()
            val stoutLevel = project.gradle.startParameter.logLevel
            val stdout = System.out
            if (stoutLevel > LogLevel.INFO) {
                System.setOut(PrintStream(NullOutputStream()))
            }
            try {
                ConsoleTool.main(args)
            } catch (e: Throwable) {
                e.printStackTrace()
                patchedMC.path.deleteIfExists()
                throw e
            }
            if (stoutLevel > LogLevel.INFO) {
                System.setOut(stdout)
            }
        }
        //   shade in forge jar
        val shadedForge = super.transform(patchedMC)
        return if (userdevCfg["notchObf"]?.asBoolean == true) {
            provider.mcRemapper.provide(shadedForge, "searge", "searge")
        } else {
            shadedForge
        }
    }

    val legacyClasspath = provider.parent.getLocalCache().createDirectories().resolve("legacy_classpath.txt")

    private fun getArgValue(config: RunConfig, arg: String): String {
        return when (arg) {
            "{minecraft_classpath_file}" -> {
                legacyClasspath.toString()
            }

            "{modules}" -> {
                val libs = mapOf(*provider.mcLibraries.dependencies.map { it.group + ":" + it.name + ":" + it.version to it }
                    .toTypedArray())
                userdevCfg.get("modules").asJsonArray.joinToString(File.pathSeparator) {
                    val dep = libs[it.asString]
                        ?: throw IllegalStateException("Module ${it.asString} not found in mc libraries")
                    provider.mcLibraries.getFile(dep).toString()
                }
            }

            "{assets_root}" -> {
                val assetsDir = provider.minecraft.metadata.assetIndex?.let {
                    provider.assetsDownloader.assetsDir()
                }
                (assetsDir ?: provider.clientWorkingDirectory.get().resolve("assets").toPath()).toString()
            }

            "{asset_index}" -> provider.minecraft.metadata.assetIndex?.id ?: ""
            "{source_roots}" -> {
                (listOf(config.commonClasspath.output.resourcesDir) + config.commonClasspath.output.classesDirs + detectOtherProjectSourceSetOutputs().flatMap {
                    listOf(
                        it.output.resourcesDir
                    ) + it.output.classesDirs
                }).joinToString(
                    File.pathSeparator
                ) { "mod%%$it" }
            }
            "{mcp_mappings}" -> "unimined.stub"
            "{natives}" -> {
                val nativesDir = provider.clientWorkingDirectory.get().resolve("natives").toPath()
                nativesDir.createDirectories()
                nativesDir.toString()
            }
            "forgeclientuserdev" -> {
                "uniminedclientuserdev"
            }
            else -> {
                if (arg.startsWith("{")) throw IllegalArgumentException("Unknown arg $arg") else arg
            }
        }
    }

    private fun detectOtherProjectSourceSetOutputs(): Set<SourceSet> {
        val ss = project.extensions.getByType(SourceSetContainer::class.java)
        val launchClasspath = provider.clientSourceSets.firstOrNull() ?: provider.combinedSourceSets.firstOrNull() ?: ss.getByName("main")
        val runtimeClasspath = launchClasspath.runtimeClasspath
        val sourceSets = mutableSetOf<SourceSet>()
        val projects = project.rootProject.allprojects.filter { it != project }
        for (project in projects) {
            for (sourceSet in project.extensions.findByType(SourceSetContainer::class.java)?.asMap?.values ?: listOf()) {
                if (sourceSet.output.files.intersect(runtimeClasspath.files).isNotEmpty()) {
                    sourceSets.add(sourceSet)
                }
            }
        }
        return sourceSets
    }

    private fun createLegacyClasspath() {
//        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
//        val source = sourceSets.findByName("client") ?: sourceSets.getByName("main")

        legacyClasspath.writeText(
            (provider.mcLibraries.resolve() + provider.combined.resolve() + provider.client.resolve() + clientExtra.resolve()).joinToString(
                "\n"
            ) { it.toString() }, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    override fun applyClientRunConfig(tasks: TaskContainer, action: (RunConfig) -> Unit) {
        createLegacyClasspath()
        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = get("main").asString

            parent.tweakClassClient = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[fg3] Using legacy client run config")
                provider.provideVanillaRunClientTask(tasks) { run ->
                    run.mainClass = "net.minecraft.launchwrapper.Launch"
                    run.jvmArgs += "-Dfml.deobfuscatedEnvironment=true"
                    run.jvmArgs += "-Dfml.ignoreInvalidMinecraftCertificates=true"
                    run.jvmArgs += "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                    run.args += "--tweakClass ${parent.tweakClassClient ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"}"
                    project.logger.info("[fg3] Run config: $run")
                    action(run)
                }
            } else {
                project.logger.info("[fg3] Using new client run config")
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                val props = get("props")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                provider.provideVanillaRunClientTask(tasks) { run ->
                    run.mainClass = mainClass
                    run.args.clear()
                    run.args += args.map { getArgValue(run, it) }
                    run.jvmArgs += jvmArgs.map { getArgValue(run, it) }
                    run.jvmArgs += props.map { "-D${it.key}=${getArgValue(run, it.value)}" }
                    run.jvmArgs += "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                    run.env += mapOf("FORGE_SPEC" to userdevCfg.get("spec").asNumber.toString())
                    run.env += env.map { it.key to getArgValue(run, it.value) }
                    project.logger.info("[fg3] Run config: $run")
                    action(run)
                }
            }
        }
    }

    override fun applyServerRunConfig(tasks: TaskContainer, action: (RunConfig) -> Unit) {
        userdevCfg.get("runs").asJsonObject.get("server").asJsonObject.apply {
            val mainClass = get("main").asString

            parent.tweakClassClient = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                project.logger.info("[fg3] Using legacy client run config")
                provider.provideVanillaRunServerTask(tasks) {
                    it.mainClass = "net.minecraft.launchwrapper.Launch"
                    it.jvmArgs += "-Dfml.ignoreInvalidMinecraftCertificates=true"
                    it.jvmArgs += "-Dfml.deobfuscatedEnvironment=true"
                    it.jvmArgs += "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMCPAsSRG}"
                    it.args += "--tweakClass ${parent.tweakClassServer ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"}"
                    project.logger.info("[fg3] Run config: $it")
                    action(it)
                }
            } else {
                project.logger.info("[fg3] Using new client run config")
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                val props = get("props")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()
                provider.provideVanillaRunServerTask(tasks) { run ->
                    run.mainClass = mainClass
                    run.args.clear()
                    run.args += args.map { getArgValue(run, it) }
                    run.jvmArgs += jvmArgs.map { getArgValue(run, it) }
                    run.jvmArgs += props.map { "-D${it.key}=${getArgValue(run, it.value)}" }
                    run.env += mapOf("FORGE_SPEC" to userdevCfg.get("spec").asNumber.toString())
                    run.env += env.map { it.key to getArgValue(run, it.value) }
                    project.logger.info("[fg3] Run config: $run")
                    action(run)
                }
            }
        }
    }

    override fun afterRemap(baseMinecraft: MinecraftJar): MinecraftJar {
        val out = fixForge(baseMinecraft)
        ZipReader.openZipFileSystem(out.path).use { fs ->
            return parent.applyATs(out, listOf(
                fs.getPath("fml_at.cfg"), fs.getPath("forge_at.cfg"), fs.getPath("META-INF/accesstransformer.cfg")
            ).filter { Files.exists(it) })
        }
    }

    private fun fixForge(baseMinecraft: MinecraftJar): MinecraftJar {
        if (baseMinecraft.mappingNamespace == "named") {
            val target = MinecraftJar(
                baseMinecraft,
                patches = baseMinecraft.patches + "fixForge",
            )

            if (target.path.exists() && !project.gradle.startParameter.isRefreshDependencies) {
                return target
            }

            Files.copy(baseMinecraft.path, target.path, StandardCopyOption.REPLACE_EXISTING)
            val mc = URI.create("jar:${target.path.toUri()}")
            try {
                FileSystems.newFileSystem(mc, mapOf("mutable" to true), null).use { out ->
                    out.getPath("binpatches.pack.lzma").deleteIfExists()
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            return target
        }
        return baseMinecraft
    }

    abstract class RemoveNameProvider : TransformAction<TransformParameters.None> {
        @get:InputArtifact
        abstract val input: Provider<FileSystemLocation>

        override fun transform(outputs: TransformOutputs) {
            val input = input.get().asFile
            println("transforming $input")
            val output = outputs.file(input.nameWithoutExtension + "-unimined-transformed.jar")
            Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            ZipReader.openZipFileSystem(output.toPath(), mapOf("mutable" to true)).use { fs ->
                fs.getPath("META-INF/services/cpw.mods.modlauncher.api.INameMappingService").deleteIfExists()
            }
        }
    }
}