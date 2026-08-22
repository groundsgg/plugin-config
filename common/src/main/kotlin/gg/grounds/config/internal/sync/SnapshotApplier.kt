package gg.grounds.config.internal.sync

import gg.grounds.config.ConfigKey
import gg.grounds.config.ConfigSnapshot
import gg.grounds.config.client.ConfigDocumentData
import gg.grounds.config.internal.binding.ConfigBinding
import gg.grounds.config.internal.cache.ConfigSnapshotCache
import gg.grounds.config.internal.scope.AppEnvScope
import org.slf4j.Logger
import tools.jackson.databind.ObjectMapper

internal class SnapshotApplier(
    private val logger: Logger,
    private val objectMapper: ObjectMapper,
    private val snapshotCacheProvider: () -> ConfigSnapshotCache,
) {
    fun applySnapshot(scope: AppEnvScope, version: Long, documents: List<ConfigDocumentData>) {
        val currentVersion = scope.version()
        if (version < currentVersion) {
            logger.warn(
                "Config snapshot ignored (app={}, env={}, version={}, currentVersion={}, documents={})",
                scope.app,
                scope.env,
                version,
                currentVersion,
                documents.size,
            )
            return
        }
        if (version == currentVersion) {
            applyUninitializedBindings(scope, version, currentVersion, documents)
            return
        }
        val documentsByKey =
            documents.associateBy { document -> ConfigKey(document.namespace, document.configKey) }
        val bindings = scope.bindingsSnapshot()
        for ((configKey, binding) in bindings) {
            val document = documentsByKey[configKey]
            if (document != null) {
                applyDocument(binding, document.contentJson)
            } else {
                handleMissingDocument(scope, binding)
            }
        }
        scope.setVersion(version)
        snapshotCacheProvider()
            .save(
                scope.app,
                scope.env,
                ConfigSnapshot(version, buildCachedDocuments(documentsByKey)),
            )
        val message =
            "Config snapshot applied (app={}, env={}, version={}, previousVersion={}, documents={})"
        if (documents.isEmpty()) {
            logger.debug(message, scope.app, scope.env, version, currentVersion, documents.size)
        } else {
            logger.info(message, scope.app, scope.env, version, currentVersion, documents.size)
        }
    }

    private fun applyUninitializedBindings(
        scope: AppEnvScope,
        version: Long,
        currentVersion: Long,
        documents: List<ConfigDocumentData>,
    ) {
        val bindings = scope.bindingsSnapshot().filterValues { !it.initialized() }
        if (bindings.isEmpty()) {
            logger.debug(
                "Config snapshot ignored (app={}, env={}, version={}, currentVersion={}, documents={}, reason=version_not_newer)",
                scope.app,
                scope.env,
                version,
                currentVersion,
                documents.size,
            )
            return
        }
        val documentsByKey =
            documents.associateBy { document -> ConfigKey(document.namespace, document.configKey) }
        for ((configKey, binding) in bindings) {
            val document = documentsByKey[configKey]
            if (document != null) {
                applyDocument(binding, document.contentJson)
            } else {
                handleMissingDocument(scope, binding)
            }
        }
    }

    fun applyCachedSnapshot(scope: AppEnvScope, snapshot: ConfigSnapshot) {
        val documents =
            snapshot.documents.map { (configKey, contentJson) ->
                ConfigDocumentData(
                    namespace = configKey.namespace,
                    configKey = configKey.configKey,
                    contentJson = contentJson,
                )
            }
        applySnapshot(scope, snapshot.version, documents)
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyDocument(binding: ConfigBinding<*>, contentJson: String) {
        try {
            val typedBinding = binding as ConfigBinding<Any>
            val value = objectMapper.readValue(contentJson, typedBinding.definition.type)
            typedBinding.update(value)
        } catch (error: Exception) {
            logger.warn(
                "Config deserialize failed (namespace={}, key={}, type={})",
                binding.definition.namespace,
                binding.definition.key,
                binding.definition.type.simpleName,
                error,
            )
        }
    }

    private fun buildCachedDocuments(
        documentsByKey: Map<ConfigKey, ConfigDocumentData>
    ): Map<ConfigKey, String> = documentsByKey.mapValuesTo(linkedMapOf()) { it.value.contentJson }

    private fun handleMissingDocument(scope: AppEnvScope, binding: ConfigBinding<*>) {
        if (binding.initialized()) {
            logger.warn(
                "Config document missing from snapshot (app={}, env={}, namespace={}, key={}, reason=missing_document, action=kept_previous_value)",
                scope.app,
                scope.env,
                binding.definition.namespace,
                binding.definition.key,
            )
            return
        }
        logger.error(
            "Config document missing from snapshot (app={}, env={}, namespace={}, key={}, reason=missing_document, action=left_uninitialized)",
            scope.app,
            scope.env,
            binding.definition.namespace,
            binding.definition.key,
        )
    }
}
