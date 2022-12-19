package xyz.wagyourtail.unimined.mappings

import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingWriter
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.MCPWriter
import net.fabricmc.mappingio.format.MappingDstNsFilter
import net.fabricmc.mappingio.format.SrgWriter
import net.fabricmc.mappingio.format.Tiny2Writer2
import net.fabricmc.mappingio.tree.MappingTreeView
import org.gradle.api.tasks.TaskAction
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.api.tasks.MappingExport
import xyz.wagyourtail.unimined.api.tasks.MappingExportTask
import xyz.wagyourtail.unimined.api.tasks.MappingExportTypes
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import kotlin.io.path.outputStream

open class MappingExportTaskImpl : MappingExportTask() {
    private val minecraftProvider = project.extensions.getByType(MinecraftProvider::class.java)
    private val mappingsProvider = project.extensions.getByType(MappingsProviderImpl::class.java)

    private val exports: MutableMap<EnvType, MutableSet<MappingExport>> = mutableMapOf()

    @TaskAction
    fun run() {
        project.logger.lifecycle("Exporting mappings...")
        for (envType in EnvType.values()) {
            if (envType == EnvType.COMBINED && minecraftProvider.disableCombined.get()) {
                if (exports.containsKey(EnvType.COMBINED) && exports[EnvType.COMBINED]!!.isNotEmpty()) {
                    project.logger.warn("Cannot export combined mappings when combined is disabled.")
                }
                continue
            }
            val mappings = mappingsProvider.getMappingTree(envType)
            val exportSet = exports[envType] ?: continue
            for (export in exportSet) {
                project.logger.info("Exporting mappings to ${export.location}")
                project.logger.debug(
                    "${export.type} ${export.sourceNamespace} -> [${
                        export.targetNamespace?.joinToString(
                            ", "
                        )
                    }}]"
                )
                export.exportFunc(mappings)
            }
        }
    }

    override fun export(envType: String, export: (MappingExport) -> Unit) {
        addExport(EnvType.valueOf(envType), export)
    }

    @ApiStatus.Internal
    override fun addExport(envType: EnvType, export: (MappingExport) -> Unit) {
        val me = MappingExportImpl(envType)
        export(me)
        me.validate()
        exports.computeIfAbsent(envType) { mutableSetOf() }.add(me)
    }
}

class MappingExportImpl(envType: EnvType) : MappingExport(envType) {

    @set:ApiStatus.Internal
    override var exportFunc: (MappingTreeView) -> Unit = ::export

    private fun export(mappingTree: MappingTreeView) {
        location!!.parentFile.mkdirs()
        location!!.toPath()
            .outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { os ->
                var visitor = when (type) {
                    MappingExportTypes.MCP -> MCPWriter(os, envType.ordinal)
                    MappingExportTypes.TINY_V2 -> Tiny2Writer2(
                        OutputStreamWriter(os, StandardCharsets.UTF_8),
                        false
                    )

                    MappingExportTypes.SRG -> SrgWriter(OutputStreamWriter(os, StandardCharsets.UTF_8))
                    else -> throw RuntimeException("Unknown export type $type")
                }
                if (skipComments) {
                    visitor = object : MappingWriter by visitor {
                        override fun visitComment(targetKind: MappedElementKind?, comment: String?) {
                            // do nothing
                        }
                    }
                }
                mappingTree.accept(
                    MappingSourceNsSwitch(
                        MappingDstNsFilter(
                            visitor, targetNamespace ?: mappingTree.dstNamespaces
                        ), sourceNamespace ?: mappingTree.srcNamespace
                    )
                )
                os.flush()
                visitor.close()
            }
    }
}