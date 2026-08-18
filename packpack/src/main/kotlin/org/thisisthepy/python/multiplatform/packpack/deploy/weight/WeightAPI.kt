package org.thisisthepy.python.multiplatform.packpack.deploy.weight

import org.thisisthepy.python.multiplatform.packpack.deploy.DeployType
import org.thisisthepy.python.multiplatform.packpack.deploy.UnimplementedDeployer

/**
 * Base API for ML weight deployment
 */
open class WeightAPI : UnimplementedDeployer(DeployType.WEIGHT)
