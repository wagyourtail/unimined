package xyz.wagyourtail.unimined.mappings

import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.*
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.fabricmc.tinyremapper.IMappingProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.configurationcache.extensions.capitalized
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.Constants
import xyz.wagyourtail.unimined.api.UniminedExtension
import xyz.wagyourtail.unimined.api.mappings.MappingsProvider
import xyz.wagyourtail.unimined.api.mappings.MemoryMapping
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftProvider
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

abstract class MappingsProviderImpl(
    val project: Project, val parent: UniminedExtension
) : MappingsProvider() {
    private val mcProvider = project.extensions.getByType(MinecraftProvider::class.java)

    init {
        for (envType in EnvType.values()) {
            getMappings(envType)
        }
        project.tasks.register("exportMappings", MappingExportTaskImpl::class.java) {
            it.group = "unimined"
            it.description = "Exports mappings to various formats."
        }
    }

    private fun getOfficialMappings(): MemoryMappingTree {
        val off = project.configurations.detachedConfiguration(
            project.dependencies.create(
                "net.minecraft:minecraft:${mcProvider.minecraft.version}:client-mappings"
            )
        )
        val file = off.resolve().first { it.extension == "txt" }
        val tree = MemoryMappingTree()
        file.inputStream()
            .use { ProGuardReader.read(it.reader(), "mojmap", "official",
                MappingSourceNsSwitch(tree, "official")
            ) }
        return tree
    }

    @ApiStatus.Internal
    final override fun getMappings(envType: EnvType): Configuration {
        return project.configurations.maybeCreate(
            Constants.MAPPINGS_PROVIDER + (envType.classifier?.capitalized() ?: "")
        )
    }

    @ApiStatus.Internal
    override fun hasStubs(envType: EnvType): Boolean {
        return stubs.contains(envType)
    }

    private val stubs = mutableMapOf<EnvType, MemoryMapping>()

    override fun getStub(envType: EnvType): MemoryMapping {
        return stubs.computeIfAbsent(envType) {
            MemoryMapping()
        }
    }

    private fun getInternalMappingsConfig(envType: EnvType): Configuration {
        return project.configurations.maybeCreate(
            Constants.MAPPINGS_INTERNAL + (envType.classifier?.capitalized() ?: "")
        )
    }

    private val mappingTrees = mutableMapOf<EnvType, MappingTreeView>()

    @ApiStatus.Internal
    override fun getMappingTree(envType: EnvType): MappingTreeView {
        return mappingTrees.computeIfAbsent(envType, ::resolveMappingTree)
    }

    private val combinedNamesMap = mutableMapOf<EnvType, String>()

    @ApiStatus.Internal
    override fun getCombinedNames(envType: EnvType): String {
        return combinedNamesMap.computeIfAbsent(envType) { env ->
            val thisEnv = getMappings(envType).dependencies.toMutableSet()
            if (envType != EnvType.COMBINED) {
                thisEnv.addAll(getMappings(EnvType.COMBINED).dependencies ?: setOf())
            }
            val mapping = thisEnv.sortedBy { "${it.name}-${it.version}" }
                .map { it.name + "-" + it.version } + if (stubs.contains(envType)) listOf("stub-${getStub(env).hash}") else listOf()

            mapping.joinToString("-")
        }
    }

    private val mappingFileEnvs = mutableMapOf<EnvType, Set<File>>()

    @Synchronized
    private fun mappingsFiles(envType: EnvType): Set<File> {
        return if (envType != EnvType.COMBINED) {
            mappingsFiles(EnvType.COMBINED)
        } else {
            setOf()
        } + mappingFileEnvs.computeIfAbsent(envType) {
            getMappings(envType).resolve()
        }
    }

    private fun writeToCache(file: Path, mappingTree: MappingTreeView) {
        file.parent.createDirectories()
        val filtered = MemoryMappingTree()
        mappingTree.accept(
            MappingDstNsFilter(
                filtered,
                listOf("intermediary", "searge", "named").filter { mappingTree.dstNamespaces.contains(it) })
        )
        ZipOutputStream(file.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)).use {
            it.putNextEntry(ZipEntry("mappings/mappings.tiny"))
            OutputStreamWriter(it).let { writer ->
                filtered.accept(Tiny2Writer2(writer, false))
                writer.flush()
            }
            it.closeEntry()
        }
    }

    @ApiStatus.Internal
    fun mappingCacheFile(envType: EnvType): Path =
        (if (stubs.contains(envType)) parent.getLocalCache() else parent.getGlobalCache()).resolve("mappings")
            .resolve("mappings-${getCombinedNames(envType)}-${envType}.jar")

    private fun resolveMappingTree(envType: EnvType): MemoryMappingTree {
        project.logger.lifecycle("Resolving mappings for $envType")
        val hasStub = stubs.contains(envType)
        val file = mappingCacheFile(envType)
        val mappingTree = MemoryMappingTree()
        if (file.exists()) {
            ZipInputStream(file.inputStream()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry ?: throw IllegalStateException("Mappings jar is empty $file")
                while (!entry!!.name.endsWith("mappings.tiny")) {
                    entry = zip.nextEntry
                    if (entry == null) {
                        throw IllegalStateException("No mappings.tiny found in $file")
                    }
                }
                project.logger.info("Found mappings.tiny in $file at ${entry.name}")
                Tiny2Reader2.read(InputStreamReader(zip), mappingTree)
            }
        } else {
            for (mapping in mappingsFiles(envType)) {
                project.logger.info(
                    "mapping ns's already read: ${mappingTree.srcNamespace}, ${
                        mappingTree.dstNamespaces?.joinToString(
                            ", "
                        )
                    }"
                )
                project.logger.info("Reading mappings from $mapping")
                if (mapping.extension == "zip" || mapping.extension == "jar") {
                    val contents = ZipReader.readContents(mapping.toPath())
                    project.logger.info("Detected mapping type: ${ZipReader.getZipTypeFromContentList(contents)}")
                    ZipReader.readMappings(envType, mapping.toPath(), contents, mappingTree)
                } else if (mapping.name == "client_mappings.txt" || mapping.name == "server_mappings.txt") {
                    project.logger.info("Detected proguard mappings")
                    InputStreamReader(mapping.inputStream()).use {
                        ProGuardReader.read(it, "named", "official",
                            MappingSourceNsSwitch(
                                mappingTree,
                                "official"
                            )
                        )
                    }
                } else {
                    throw IllegalStateException("Unknown mapping file type ${mapping.name}")
                }
            }
            if (hasStub) {
                getStub(envType).visit(mappingTree)
            }
            if (mappingTree.dstNamespaces.contains("srg")) {
                project.logger.info("Detected TSRG2 mappings (1.17+) - converting to have the right class names for runtime forge")
                // read mojmap (possible again, TODO: detect if already there on named)
                val mojmap = getOfficialMappings()
                mojmap.accept(mappingTree)
                SeargeFromTsrg2.apply("srg", "mojmap", "searge", mappingTree)
            }
            writeToCache(file, mappingTree)
        }
        getInternalMappingsConfig(envType).dependencies.add(
            project.dependencies.create(
                project.files(file.toFile())
            )
        )

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        if (envType == EnvType.COMBINED) {
            mcProvider.combinedSourceSets.forEach {
                it.runtimeClasspath += getInternalMappingsConfig(envType)
            }
        }
        if (envType == EnvType.CLIENT) {
            mcProvider.clientSourceSets.forEach {
                it.runtimeClasspath += getInternalMappingsConfig(envType)
            }
        }
        if (envType == EnvType.SERVER) {
            mcProvider.serverSourceSets.forEach {
                it.runtimeClasspath += getInternalMappingsConfig(envType)
            }
        }
        project.logger.info(
            "mappings for $envType, srcNamespace: ${mappingTree.srcNamespace} dstNamespaces: ${
                mappingTree.dstNamespaces.joinToString(
                    ","
                )
            }"
        )
        return mappingTree
    }


    private fun memberOf(className: String, memberName: String, descriptor: String?): IMappingProvider.Member {
        return IMappingProvider.Member(className, memberName, descriptor)
    }

    @ApiStatus.Internal
    override fun getMappingProvider(
        envType: EnvType,
        srcName: String,
        fallbackSrc: String,
        fallbackTarget: String,
        targetName: String,
        remapLocalVariables: Boolean,
    ): (IMappingProvider.MappingAcceptor) -> Unit {
        val mappingTree = getMappingTree(envType)
        return { acceptor: IMappingProvider.MappingAcceptor ->
            val fromId = mappingTree.getNamespaceId(srcName)
            var fallbackSrcId = mappingTree.getNamespaceId(fallbackSrc)
            var fallbackToId = mappingTree.getNamespaceId(fallbackTarget)
            val toId = mappingTree.getNamespaceId(targetName)

            if (fromId == MappingTreeView.NULL_NAMESPACE_ID) {
                throw RuntimeException("Namespace $srcName not found in mappings")
            }

            if (toId == MappingTreeView.NULL_NAMESPACE_ID) {
                throw RuntimeException("Namespace $targetName not found in mappings")
            }

            if (fallbackToId == MappingTreeView.NULL_NAMESPACE_ID) {
                project.logger.warn("Namespace $fallbackTarget not found in mappings, using $srcName as fallback")
                fallbackToId = fromId
            }

            if (fallbackSrcId == MappingTreeView.NULL_NAMESPACE_ID) {
                project.logger.warn("Namespace $fallbackSrc not found in mappings, using $srcName as fallback")
                fallbackSrcId = fromId
            }

            project.logger.info("Mapping from $srcName to $targetName, fallbackSrc: $fallbackSrc, fallbackTarget: $fallbackTarget")
            project.logger.info("ids: from $fromId to $toId fallbackTo $fallbackToId fallbackFrom $fallbackSrcId")

            for (classDef in mappingTree.classes) {
                var fromClassName = classDef.getName(fromId) ?: classDef.getName(fallbackSrcId)
                var toClassName = classDef.getName(toId) ?: classDef.getName(fallbackToId)

                if (fromClassName == null) {
                    project.logger.debug("From class name not found for $classDef")
                    fromClassName = toClassName
                }

                // detect missing inner class
                if (fromClassName != null && fromClassName.contains("$")) {
                    val outerClass = fromClassName.substringBefore("$")
                    val outerClassDef = mappingTree.getClass(outerClass, fromId) ?: mappingTree.getClass(outerClass, fallbackSrcId)
                    if (outerClassDef != null) {
                        val outerToClassName = outerClassDef.getName(toId) ?: outerClassDef.getName(fallbackToId)
                        val innerClassName = toClassName?.substringAfter("$") ?: fromClassName.substringAfter("$")
                        if (outerToClassName != null && (toClassName == null || !toClassName.startsWith(outerToClassName))) {
                            toClassName = "$outerToClassName$$innerClassName"
                            project.logger.warn("Detected missing inner class, replacing with: $fromClassName -> $toClassName")
                        }
                    }
                }

                if (toClassName == null) {
                    project.logger.debug("To class name not found for $classDef")
                    toClassName = fromClassName
                }

                if (fromClassName == null) {
                    project.logger.error("Class name not found for $classDef")
                }

                project.logger.debug("fromClassName: $fromClassName toClassName: $toClassName")
                if (fromClassName != null) {
                    acceptor.acceptClass(fromClassName, toClassName)
                }

                for (fieldDef in classDef.fields) {
                    var fromFieldName = fieldDef.getName(fromId) ?: fieldDef.getName(fallbackSrcId)
                    var toFieldName = fieldDef.getName(toId) ?: fieldDef.getName(fallbackToId)
                    val fromFieldDesc = fieldDef.getDesc(fromId) ?: fieldDef.getDesc(fallbackSrcId)

                    if (fromFieldName == null) {
                        project.logger.debug("From field name not found for $fieldDef")
                        fromFieldName = toFieldName;
                    }

                    if (toFieldName == null) {
                        project.logger.debug("To field name not found for $fieldDef")
                        toFieldName = fromFieldName
                    }

                    if (fromFieldName == null) {
                        project.logger.error("Field name not found for $fieldDef")
                    }

                    if (toFieldName != null) {
                        project.logger.debug("fromFieldName: $fromFieldName toFieldName: $toFieldName")
                        acceptor.acceptField(
                            memberOf(fromClassName, fromFieldName, fromFieldDesc), toFieldName
                        )
                    }
                }

                for (methodDef in classDef.methods) {
                    val fromMethodName = methodDef.getName(fromId) ?: methodDef.getName(fallbackSrcId)
                    var toMethodName = methodDef.getName(toId) ?: methodDef.getName(fallbackToId)
                    // TODO: custom method for this... to merge with fallback instead of replacing
                    val fromMethodDesc = methodDef.getDesc(fromId) ?: methodDef.getDesc(fallbackSrcId)

                    if (toMethodName == null) {
                        project.logger.debug("To Method name not found for $methodDef")
                        toMethodName = fromMethodName
                    }

                    if (fromMethodName == null) {
                        project.logger.error("Method name not found for $methodDef")
                    }

                    if (toMethodName != null) {
                        project.logger.debug("fromMethodName: $fromMethodName toMethodName: $toMethodName")
                        val method = memberOf(fromClassName, fromMethodName, fromMethodDesc)

                        project.logger.debug("fromMethodName: $fromMethodName toMethodName: $toMethodName")
                        acceptor.acceptMethod(method, toMethodName)

                        if (remapLocalVariables) {
                            for (arg in methodDef.args) {
                                val toArgName = arg.getName(toId) ?: arg.getName(fallbackToId) ?: continue
                                acceptor.acceptMethodArg(method, arg.lvIndex, toArgName)
                            }

                            for (localVar in methodDef.vars) {
                                val toLocalVarName = localVar.getName(toId) ?: localVar.getName(fallbackToId) ?: continue
                                acceptor.acceptMethodVar(
                                    method, localVar.lvIndex, localVar.startOpIdx, localVar.lvtRowIndex, toLocalVarName
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}