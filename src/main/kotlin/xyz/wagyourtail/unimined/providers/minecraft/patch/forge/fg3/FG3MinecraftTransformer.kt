package xyz.wagyourtail.unimined.providers.minecraft.patch.forge.fg3

import com.google.gson.JsonParser
import net.fabricmc.mappingio.format.ZipReader
import net.minecraftforge.binarypatcher.ConsoleTool
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.TaskContainer
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.*
import xyz.wagyourtail.unimined.providers.minecraft.EnvType
import xyz.wagyourtail.unimined.providers.minecraft.patch.MinecraftJar
import xyz.wagyourtail.unimined.providers.minecraft.patch.forge.AccessTransformerMinecraftTransformer
import xyz.wagyourtail.unimined.providers.minecraft.patch.forge.ForgeMinecraftTransformer
import xyz.wagyourtail.unimined.providers.minecraft.patch.forge.fg3.mcpconfig.McpConfigData
import xyz.wagyourtail.unimined.providers.minecraft.patch.forge.fg3.mcpconfig.McpConfigStep
import xyz.wagyourtail.unimined.providers.minecraft.patch.forge.fg3.mcpconfig.McpExecutor
import xyz.wagyourtail.unimined.providers.minecraft.patch.jarmod.JarModMinecraftTransformer
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.nio.file.*
import kotlin.io.path.*

class FG3MinecraftTransformer(project: Project, val parent: ForgeMinecraftTransformer) : JarModMinecraftTransformer(
    project,
    parent.provider,
    Constants.FORGE_PROVIDER
) {

    @ApiStatus.Internal
    val forgeUd = project.configurations.maybeCreate(Constants.FORGE_USERDEV)

    val forgeInstaller = project.configurations.maybeCreate(Constants.FORGE_INSTALLER)

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
        val userdevClassifier = ZipReader.readInputStreamFor<String?>("binpatches.pack.lzma", forgeUni.toPath(), false) {
            "userdev3"
        } ?: "userdev"

        val userdev = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:$userdevClassifier"
        forgeUd.dependencies.add(project.dependencies.create(userdev))

//        val installer = "${forgeDep.group}:${forgeDep.name}:${forgeDep.version}:installer"
//        forgeInstaller.dependencies.add(project.dependencies.create(installer))

        provider.parent.mappingsProvider.getMappings(EnvType.COMBINED).dependencies.apply {
            val empty = isEmpty()
            mcpConfig = project.dependencies.create("de.oceanlabs.mcp:mcp_config:${provider.minecraftDownloader.version}@zip")
            add(mcpConfig)
            if (empty) {
                if (parent.mcpVersion == null || parent.mcpChannel == null) throw IllegalStateException("mcpVersion and mcpChannel must be set in forge block for 1.7+")
                add(project.dependencies.create("de.oceanlabs.mcp:mcp_${parent.mcpChannel}:${parent.mcpVersion}@zip"))
            }
        }

        for (element in userdevCfg.get("libraries")?.asJsonArray ?: listOf()) {
            if (element.asString.contains("legacydev")) continue
            provider.mcLibraries.dependencies.add(
                project.dependencies.create(
                    element.asString.replace(".+", "")

                )
            )
        }
        super.afterEvaluate()
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
        val steps: List<McpConfigStep> = mcpConfigData.steps.get(type)!!
        val executor = McpExecutor(
            project,
            provider,
            outputPath.parent.resolve("mcpconfig").maybeCreate(),
            steps,
            mcpConfigData.functions
        )
        val output: Path = executor.executeUpTo(step)
        Files.copy(output, outputPath, StandardCopyOption.REPLACE_EXISTING)
    }



    override fun merge(clientjar: MinecraftJar, serverjar: MinecraftJar, output: Path): MinecraftJar {
        project.logger.warn("Merging client and server jars...")
        val patched = if (userdevCfg["notchObf"]?.asBoolean == true) {
            executeMcp("merge", output, EnvType.COMBINED)
            MinecraftJar(clientjar, output, EnvType.COMBINED)
        } else {
            executeMcp("rename", output, EnvType.COMBINED)
            MinecraftJar(clientjar, output, EnvType.COMBINED, "searge")
        }
        // unstrip resources
        return unstripResources(clientjar, serverjar, patched)
    }

    private fun unstripResources(baseMinecraftClient: MinecraftJar, baseMinecraftServer: MinecraftJar, patchedMinecraft: MinecraftJar): MinecraftJar {
        val unstripped = patchedMinecraft.jarPath.parent.resolve("${patchedMinecraft.jarPath.nameWithoutExtension}-unstripped.jar")
        patchedMinecraft.jarPath.copyTo(unstripped, StandardCopyOption.REPLACE_EXISTING)
        ZipReader.openZipFileSystem(unstripped, mapOf("mutable" to true)).use { unstripped ->
            ZipReader.openZipFileSystem(baseMinecraftClient.jarPath).use { base ->
                unstrip(base, unstripped)
            }
            ZipReader.openZipFileSystem(baseMinecraftServer.jarPath).use { base ->
                unstrip(base, unstripped)
            }
        }
        return MinecraftJar(patchedMinecraft, unstripped)
    }

    private fun unstrip(inp: FileSystem, out: FileSystem) {
        for (path in Files.walk(inp.getPath("/"))) {
//            project.logger.warn("Checking $path")
            if (!path.isDirectory() && path.extension != "class") {
//                project.logger.warn("Copying $path")
                val target = out.getPath(path.toString())
                target.parent.maybeCreate()
                path.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        project.logger.warn("transforming minecraft jar $minecraft for FG3")
        val atMcJar = minecraft.let(consumerApply {
            val forgeUniversal = parent.forge.dependencies.last()
            val forgeUd = forgeUd.getFile(forgeUd.dependencies.last())

            // get forge jar
            val forge = parent.forge.getFile(forgeUniversal)

            val outFolder = minecraft.jarPath.parent.resolve("${forgeUniversal.name}-${forgeUniversal.version}").maybeCreate()

            // if userdev cfg says notch
            if (userdevCfg["notchObf"]?.asBoolean == true && envType != EnvType.COMBINED) {
                throw IllegalStateException("Forge userdev3 (legacy fg3, aka 1.12.2) is not supported for non-combined environments.")
            }

            //   apply binpatches
            val binPatchFile = ZipReader.readInputStreamFor(userdevCfg["binpatches"].asString, forgeUd.toPath()) {
                outFolder.resolve("binpatches.pack.lzma").apply { writeBytes(it.readBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) }
            }

            val patchedMC = outFolder.resolve("${jarPath.nameWithoutExtension}-${forgeUniversal.name}-${forgeUniversal.version}.${jarPath.extension}")
            if (!patchedMC.exists() || project.gradle.startParameter.isRefreshDependencies) {
                patchedMC.deleteIfExists()
                val args = (userdevCfg["binpatcher"].asJsonObject["args"].asJsonArray.map {
                    when (it.asString) {
                        "{clean}" -> jarPath.toString()
                        "{patch}" -> binPatchFile.toString()
                        "{output}" -> patchedMC.toString()
                        else -> it.asString
                    }
                } + listOf("--data", "--unpatched")).toTypedArray()
                try {
                    ConsoleTool.main(args)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    patchedMC.deleteIfExists()
                    throw e
                }
            }
            //   apply at's
            val accessModder = AccessTransformerMinecraftTransformer(project, provider, envType).apply {
                if (userdevCfg["notchObf"]?.asBoolean == true) {
                    atTransformers.add {
                        remapTransformer(
                            envType,
                            it,
                            "named", "searge", "official", "official"
                        )
                    }
                } else {
                    atTransformers.add {
                        remapTransformer(
                            envType,
                            it,
                            "named", "named", "searge", "searge"
                        )
                    }
                }

                for (at in userdevCfg["ats"].asJsonArray) {
                    ZipReader.readInputStreamFor(at.asString, forgeUd.toPath()) {
                        addAccessTransformer(it)
                    }
                }

                parent.accessTransformer?.let { addAccessTransformer(it) }
            }

            accessModder.transform(MinecraftJar(this, patchedMC))
        })

        //   shade in forge jar
        return super.transform(atMcJar)
    }

    private fun getArgValue(arg: String): String {
        if (arg.startsWith("{")) {
            return when (arg) {
                "{modules}" -> TODO()
                "{assets_root}" -> {
                    val assetsDir = provider.minecraftDownloader.metadata.assetIndex?.let {
                        provider.assetsDownloader.downloadAssets(project, it)
                    }
                    (assetsDir ?: provider.clientWorkingDirectory.get().resolve("assets").toPath()).toString()
                }
                "{asset_index}" -> provider.minecraftDownloader.metadata.assetIndex?.id ?: ""
                "{source_roots}" -> TODO()
                "{mcp_mappings}" -> TODO()
                else -> throw IllegalArgumentException("Unknown arg $arg")
            }
        } else {
            return arg
        }
    }

    override fun applyClientRunConfig(tasks: TaskContainer) {
        userdevCfg.get("runs").asJsonObject.get("client").asJsonObject.apply {
            val mainClass = get("main").asString

            provider.overrideMainClassClient.set(mainClass)
            parent.tweakClass = get("env")?.asJsonObject?.get("tweakClass")?.asString
            if (mainClass.startsWith("net.minecraftforge.legacydev")) {
                provider.provideRunClientTask(tasks) {
                    it.mainClass = "net.minecraft.launchwrapper.Launch"
                    it.jvmArgs += "-Dfml.ignoreInvalidMinecraftCertificates=true"
                    it.jvmArgs += "-Dnet.minecraftforge.gradle.GradleStart.srg.srg-mcp=${parent.srgToMcpMappings}"
                    it.args += "--tweakClass ${parent.tweakClass ?: "net.minecraftforge.fml.common.launcher.FMLTweaker"}"
                }
            } else {
                val args = get("args")?.asJsonArray?.map { it.asString } ?: listOf()
                val jvmArgs = get("jvmArgs")?.asJsonArray?.map { it.asString } ?: listOf()
                val env = get("env")?.asJsonObject?.entrySet()?.associate { it.key to it.value.asString } ?: mapOf()

            }
        }
    }

    override fun afterRemap(envType: EnvType, namespace: String, baseMinecraft: Path): Path {
        if (namespace == "named") {
            val target = baseMinecraft.parent.resolve("${baseMinecraft.nameWithoutExtension}-stripped.${baseMinecraft.extension}")

            if (target.exists() && !project.gradle.startParameter.isRefreshDependencies) {
                return target
            }

            Files.copy(baseMinecraft, target, StandardCopyOption.REPLACE_EXISTING)
            val mc = URI.create("jar:${target.toUri()}")
            try {
                FileSystems.newFileSystem(mc, mapOf("mutable" to true), null).use { out ->
                    out.getPath("binpatches.pack.lzma").deleteIfExists()

                    //TODO: FIXME, hack. remove forge trying to transform class names for fg2 dev launch
                    out.getPath("net/minecraftforge/fml/common/asm/transformers/DeobfuscationTransformer.class").deleteIfExists()
                }
            } catch (e: Throwable) {
                target.deleteExisting()
                throw e
            }
            return target
        }
        return baseMinecraft
    }


}