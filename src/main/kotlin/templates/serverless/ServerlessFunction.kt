package templates.serverless

import io.kloudformation.KloudFormation
import io.kloudformation.Value
import io.kloudformation.property.aws.lambda.function.Code
import io.kloudformation.resource.aws.iam.Role
import io.kloudformation.resource.aws.lambda.Function
import io.kloudformation.resource.aws.lambda.function
import io.kloudformation.resource.aws.logs.LogGroup
import io.kloudformation.resource.aws.logs.logGroup
import io.kloudformation.resource.aws.s3.Bucket
import io.kloudformation.module.*

class ServerlessFunction(val logGroup: LogGroup, val role: Role?, val function: Function, val httpEvents: List<HttpModule>) : Module {

    class Predefined(var serviceName: String, var stage: String, var deploymentBucket: Bucket, var globalRole: Role?, var privateConfig: Serverless.PrivateConfig?): Properties
    class Props(val functionId: String, val codeLocationKey: Value<String>, val handler: Value<String>, val runtime: Value<String>, val privateConfig: Serverless.PrivateConfig? = null): Properties

    class Parts{
        data class LambdaProps(var code: Code, var handler: Value<String>, var role: Value<String>, var runtime: Value<String>): Properties
        val lambdaLogGroup = modification<LogGroup.Builder, LogGroup, NoProps>()
        val lambdaRole = optionalModification<Role.Builder, Role, Serverless.Parts.RoleProps>(absent = true)
        val lambdaFunction = modification<Function.Builder, Function, LambdaProps>()
        val http = SubModules({ pre: HttpModule.Predefined, props: HttpModule.Props -> HttpModule.Builder(pre, props)})
    }

    class Builder(
            pre: Predefined,
            val props: Props
    ): SubModuleBuilder<ServerlessFunction, Parts, Predefined, Props>(pre, Parts()){

        override fun KloudFormation.buildModule(): Parts.() -> ServerlessFunction = {
            val logGroupResource = lambdaLogGroup(NoProps) {
                logGroup {
                    logGroupName(+"/aws/lambda/${pre.serviceName}-${pre.stage}-${props.functionId}")
                    modifyBuilder(it)
                }
            }
            val code = Code(
                    s3Bucket = pre.deploymentBucket.ref(),
                    s3Key = props.codeLocationKey
            )
            if(pre.globalRole == null) lambdaRole.keep()
            val roleResource = Serverless.Builder.run { roleFor(pre.serviceName, pre.stage, lambdaRole) } ?: pre.globalRole
            val lambdaResource = lambdaFunction(Parts.LambdaProps(code,props.handler,roleResource?.ref() ?: +"",props.runtime)) { props ->
                function(props.code, props.handler, props.role, props.runtime,
                        dependsOn = listOfNotNull(logGroupResource.logicalName, roleResource?.logicalName)) {
                    val privateOverride = this@Builder.props.privateConfig
                    if (privateOverride !is Serverless.NoPrivateConfig) {
                        (privateOverride ?: pre.privateConfig)?.let { config ->
                            if (config.securityGroups != null && config.subnetIds != null) {
                                vpcConfig(config.securityGroups, config.subnetIds)
                            }
                        }
                    }
                    modifyBuilder(props)
                }
            }
            val httpEvents = http.modules().mapNotNull{
                it.module(HttpModule.Predefined(pre.serviceName, pre.stage))()
            }
            ServerlessFunction(logGroupResource, roleResource, lambdaResource, httpEvents)
        }
    }
}