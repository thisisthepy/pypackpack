package org.thisisthepy.python.multiplatform.packpack.deploy.resource

import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import org.thisisthepy.python.multiplatform.packpack.deploy.UnimplementedDeployer

/**
 * Base API for resource deployment
 */
open class ResourceAPI : UnimplementedDeployer(DeployType.RESOURCE)