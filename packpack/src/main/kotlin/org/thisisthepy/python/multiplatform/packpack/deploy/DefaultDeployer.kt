package org.thisisthepy.python.multiplatform.packpack.deploy

/**
 * Decorator pattern default interface for deployment
 */
class DefaultDeployer(
    type: DeployType = DeployType.CODE,
) : UnimplementedDeployer(type)