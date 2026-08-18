package org.thisisthepy.python.multiplatform.packpack.deploy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DeployInterfaceTest {
    @Test
    fun create_reportsUnimplementedDeployTypesInsteadOfPretendingToWork() {
        listOf(DeployType.CODE, DeployType.RESOURCE, DeployType.WEIGHT).forEach { type ->
            val result = DeployInterface.create(type).deploy(
                DeployRequest(packageDir = File("mypackage"), deployType = type)
            )
            assertTrue(result.isFailure, "$type must not report success while unimplemented")
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                message.contains("not implemented", ignoreCase = true),
                "$type: $message",
            )
        }
    }
}
