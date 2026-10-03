package org.thisisthepy.python.multiplatform.packpack.deploy.code

import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import org.thisisthepy.python.multiplatform.packpack.deploy.UnimplementedDeployer

/**
 * Base API for code deployment
 */
open class CodeAPI : UnimplementedDeployer(DeployType.CODE)