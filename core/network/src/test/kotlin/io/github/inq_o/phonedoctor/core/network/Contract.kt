package io.github.inq_o.phonedoctor.core.network

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/** 본문이 openapi.yaml의 스키마를 지키는지 (서버 시험의 Contract와 같은 방식: JSON Schema 2020-12, 형식 검사 포함) */
object Contract {
    private val yaml = ObjectMapper(YAMLFactory())
    private val doc = yaml.readTree(File(System.getProperty("openapi.path") ?: fail("openapi.path 시스템 속성 없음")))
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    fun errors(schema: String, body: String): Set<*> {
        val root = yaml.createObjectNode()
        root.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        root.put("\$ref", "#/components/schemas/$schema")
        root.set<ObjectNode>("components", doc["components"])
        return factory.getSchema(root, config).validate(ObjectMapper().readTree(body))
    }

    fun check(schema: String, body: String) {
        val errors = errors(schema, body)
        assertTrue(errors.isEmpty(), "$schema 계약 위반: $errors\n$body")
    }
}
