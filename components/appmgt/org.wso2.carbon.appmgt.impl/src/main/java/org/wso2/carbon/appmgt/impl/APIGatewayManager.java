/*
 * Copyright WSO2 Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.appmgt.impl;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.AxisFault;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.appmgt.api.AppManagementException;
import org.wso2.carbon.appmgt.api.model.WebApp;
import org.wso2.carbon.appmgt.impl.dto.Environment;
import org.wso2.carbon.appmgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.appmgt.impl.template.APITemplateBuilder;
import org.wso2.carbon.appmgt.impl.utils.AppManagerUtil;
import org.wso2.carbon.appmgt.impl.utils.RESTAPIAdminClient;
import org.wso2.carbon.appmgt.impl.clients.MediationSecurityAdminServiceClient;
import org.wso2.carbon.appmgt.impl.clients.SequenceAdminServiceClient;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.List;

import javax.xml.namespace.QName;

public class APIGatewayManager {

	private static final Log log = LogFactory.getLog(APIGatewayManager.class);

	private static APIGatewayManager instance;

	private List<Environment> environments;

	private boolean debugEnabled = log.isDebugEnabled();

	private APIGatewayManager() {
		AppManagerConfiguration config = ServiceReferenceHolder.getInstance()
		                                                       .getAPIManagerConfigurationService()
		                                                       .getAPIManagerConfiguration();
		environments = config.getApiGatewayEnvironments();
	}

	public synchronized static APIGatewayManager getInstance() {
		if (instance == null) {
			instance = new APIGatewayManager();
		}
		return instance;
	}

	/**
	 * Publishes an WebApp to all configured Gateways.
	 * 
	 * @param api
	 *            - The WebApp to be published
	 * @param builder
	 *            - The template builder
	 * @param tenantDomain
	 *            - Tenant Domain of the publisher
	 * @throws Exception
	 *             - Thrown when publishing to at least one Gateway fails. A
	 *             single failure will stop all
	 *             subsequent attempts to publish to other Gateways.
	 */
	public void publishToGateway(WebApp api, APITemplateBuilder builder, String tenantDomain)
	                                                                                      throws Exception {
		for (Environment environment : environments) {
			RESTAPIAdminClient client = new RESTAPIAdminClient(api.getId(), environment);
			String operation; 
			// If the WebApp exists in the Gateway
			if (client.getVersionedWebApp(tenantDomain) != null) {

				// If the Gateway type is 'production' and the production url
				// has been removed
				// Or if the Gateway type is 'sandbox' and the sandbox url has
				// been removed.
				if ((AppMConstants.GATEWAY_ENV_TYPE_PRODUCTION.equals(environment.getType()) && !AppManagerUtil.isProductionEndpointsExists(api)) ||
				    (AppMConstants.GATEWAY_ENV_TYPE_SANDBOX.equals(environment.getType()) && !AppManagerUtil.isSandboxEndpointsExists(api))) {
					if (debugEnabled) {
						log.debug("Removing WebApp " + api.getId().getApiName() +
						          " from Environment " + environment.getName() +
						          " since its relevant URL has been removed.");
					}
					// We need to remove the api from the environment since its
					// relevant url has been removed.
					operation ="delete";
					if (client.getNonVersionedWebAppData(tenantDomain) != null) {
						client.deleteNonVersionedWebApp(tenantDomain);
					}
					client.deleteVersionedWebApp(tenantDomain);
					undeployCustomSequences(api,tenantDomain, environment);
				} else {
					if (debugEnabled) {
						log.debug("WebApp exists, updating existing WebApp " + api.getId().getApiName() +
						          " in environment " + environment.getName());
					}
					operation ="update";
					client.updateVersionedWebApp(builder, tenantDomain);
					updateCustomSequences(api, tenantDomain, environment);
				}
			} else {
				// If the Gateway type is 'production' and a production url has
				// not been specified
				// Or if the Gateway type is 'sandbox' and a sandbox url has not
				// been specified
				if ((AppMConstants.GATEWAY_ENV_TYPE_PRODUCTION.equals(environment.getType()) && !AppManagerUtil.isProductionEndpointsExists(api)) ||
				    (AppMConstants.GATEWAY_ENV_TYPE_SANDBOX.equals(environment.getType()) && !AppManagerUtil.isSandboxEndpointsExists(api))) {

					if (debugEnabled) {
						log.debug("Not adding WebApp to environment " + environment.getName() +
						          " since its endpoint URL " + "cannot be found");
					}
					// Do not add the WebApp, continue loop.
					continue;
				} else {
					if (debugEnabled) {
						log.debug("WebApp does not exist, adding new WebApp " + api.getId().getApiName() +
						          " in environment " + environment.getName());
					}
					operation = "add";
					if (client.getNonVersionedWebAppData(tenantDomain) != null) {
						client.updateNonVersionedWebApp(builder, tenantDomain);
					} else {
						client.addNonVersionedWebApp(builder, tenantDomain);
					}
					client.addVersionedWebApp(builder, tenantDomain);
					deployCustomSequences(api, tenantDomain, environment);
				}
			}
		}
	}

	/**
	 * Removed an WebApp from the configured Gateways
	 * 
	 * @param api
	 *            - The WebApp to be removed
	 * @param tenantDomain
	 *            - Tenant Domain of the publisher
	 * @throws Exception
	 *             - Thrown if a failure occurs while removing the WebApp from the
	 *             Gateway. A single failure will
	 *             stop all subsequent attempts to remove from other Gateways.
	 */
	public void removeFromGateway(WebApp api, String tenantDomain) throws Exception {
		for (Environment environment : environments) {
			RESTAPIAdminClient client = new RESTAPIAdminClient(api.getId(), environment);

			if (client.getVersionedWebApp(tenantDomain) != null) {
				if (debugEnabled) {
					log.debug("Removing WebApp " + api.getId().getApiName() + " From environment " +
									  environment.getName());
				}
				String operation = "delete";
				client.deleteNonVersionedWebApp(tenantDomain);
				client.deleteVersionedWebApp(tenantDomain);
				undeployCustomSequences(api, tenantDomain, environment);
			}
		}
	}

	/**
	 * Checks whether the WebApp has been published.
	 * 
	 * @param api
	 *            - The WebApp to be cheked.
	 * @param tenantDomain
	 *            - Tenant Domain of the publisher
	 * @return True if the WebApp is available in at least one Gateway. False if
	 *         available in none.
	 * @throws Exception
	 *             - Thrown if a check to at least one Gateway fails.
	 */
	public boolean isAPIPublished(WebApp api, String tenantDomain) throws Exception {
		for (Environment environment : environments) {
			RESTAPIAdminClient client = new RESTAPIAdminClient(api.getId(), environment);
			// If the WebApp exists in at least one environment, consider as
			// published and return true.
			if (client.getVersionedWebApp(tenantDomain) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Get the specified in/out sequences from api object
	 * 
	 * @param api
	 *            -WebApp object
	 * @param tenantDomain
	 * @param environment
	 * @throws org.wso2.carbon.appmgt.api.AppManagementException
	 * @throws AxisFault
	 */
    private void deployCustomSequences(WebApp api, String tenantDomain, Environment environment)
            throws AppManagementException,
                   AxisFault {

        if (isSequenceDefined(api.getInSequence()) || isSequenceDefined(api.getOutSequence())) {
            try {
                PrivilegedCarbonContext.startTenantFlow();
                if(tenantDomain != null && !tenantDomain.equals("")){
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
                }
                else{
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain
                            (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);
                }
                int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

                if (isSequenceDefined(api.getInSequence())) {
                    deployInSequence(api, tenantId, tenantDomain, environment);
                }

                if (isSequenceDefined(api.getOutSequence())) {
                	deployOutSequence(api, tenantId, tenantDomain, environment);
                }

            } catch (Exception e) {
                String msg = "Error in deploying the sequence to gateway";
                log.error(msg, e);
                throw new AppManagementException(msg);
            }
            finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }

    }

    private void deployInSequence(WebApp api, int tenantId, String tenantDomain, Environment environment)
            throws AppManagementException, AxisFault {

        String inSeqExt = AppManagerUtil.getSequenceExtensionName(api) + "--In";
        String inSequenceName = api.getInSequence();
        OMElement inSequence = AppManagerUtil.getCustomSequence(inSequenceName, tenantId, "in");

       SequenceAdminServiceClient sequenceAdminServiceClient = new SequenceAdminServiceClient(environment);

        if (inSequence != null) {
            inSequence.getAttribute(new QName("name")).setAttributeValue(inSeqExt);
            sequenceAdminServiceClient.addSequence(inSequence, tenantDomain);
        }
    }

    private void deployOutSequence(WebApp api, int tenantId, String tenantDomain, Environment environment)
            throws AppManagementException, AxisFault {

        String outSeqExt  = AppManagerUtil.getSequenceExtensionName(api) + "--Out";
        String outSequenceName = api.getOutSequence();
        OMElement outSequence = AppManagerUtil.getCustomSequence(outSequenceName, tenantId, "out");

        SequenceAdminServiceClient sequenceAdminServiceClient = new SequenceAdminServiceClient(environment);

        if (outSequence != null) {
            outSequence.getAttribute(new QName("name")).setAttributeValue(outSeqExt);
            sequenceAdminServiceClient.addSequence(outSequence, tenantDomain);
        }
    }

	/**
	 * Undeploy the sequences deployed in synapse
	 * 
	 * @param api
	 * @param tenantDomain
	 * @param environment
	 * @throws org.wso2.carbon.appmgt.api.AppManagementException
	 */
    private void undeployCustomSequences(WebApp api, String tenantDomain, Environment environment) {

        if (isSequenceDefined(api.getInSequence()) || isSequenceDefined(api.getOutSequence())) {
            try {
                PrivilegedCarbonContext.startTenantFlow();
                if(tenantDomain != null && !tenantDomain.equals("")){
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
                }
                else{
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain
                            (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);
                }
                SequenceAdminServiceClient seqClient = new SequenceAdminServiceClient(environment);

                if (isSequenceDefined(api.getInSequence())) {
                    String inSequence = AppManagerUtil.getSequenceExtensionName(api) + "--In";
                    seqClient.deleteSequence(inSequence, tenantDomain);
                }
                if (isSequenceDefined(api.getOutSequence())) {
                    String outSequence = AppManagerUtil.getSequenceExtensionName(api) + "--Out";
                    seqClient.deleteSequence(outSequence, tenantDomain);
                }
            } catch (Exception e) {
                String msg = "Error in deleting the sequence from gateway";
                log.error(msg, e);
            } finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }
    }

    /**
	 * Update the custom sequences in gateway
	 * @param api
	 * @param tenantDomain
	 * @param environment
	 * @throws org.wso2.carbon.appmgt.api.AppManagementException
	 */
	private void updateCustomSequences(WebApp api, String tenantDomain, Environment environment)
	                                                                                         throws
                                                                                             AppManagementException {

        //If sequences have been added, updated or removed.
        if (isSequenceDefined(api.getInSequence()) || isSequenceDefined(api.getOutSequence()) ||
                isSequenceDefined(api.getOldInSequence()) || isSequenceDefined(api.getOldOutSequence())) {

            try {
                PrivilegedCarbonContext.startTenantFlow();
                if(tenantDomain != null && !tenantDomain.equals("")){
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
                }
                else{
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain
                            (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);
                }
                int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();

                SequenceAdminServiceClient seqClient = new SequenceAdminServiceClient(environment);

                //If an inSequence has been added, updated or removed.
                if (isSequenceDefined(api.getInSequence()) || isSequenceDefined(api.getOldInSequence())) {
                    String inSequenceKey = AppManagerUtil.getSequenceExtensionName(api) + "--In";
                    //If sequence already exists
                    if (seqClient.isExistingSequence(inSequenceKey, tenantDomain)) {
                        //Delete existing sequence
                        seqClient.deleteSequence(inSequenceKey, tenantDomain);
                    }
                    //If an inSequence has been added or updated.
                    if(isSequenceDefined(api.getInSequence())){
                        //Deploy the inSequence
                        deployInSequence(api, tenantId, tenantDomain, environment);
                    }
                }

                //If an outSequence has been added, updated or removed.
                if (isSequenceDefined(api.getOutSequence()) || isSequenceDefined(api.getOldOutSequence())) {
                    String outSequence = AppManagerUtil.getSequenceExtensionName(api) + "--Out";
                    //If the outSequence exists.
                    if (seqClient.isExistingSequence(outSequence, tenantDomain)) {
                        //Delete existing outSequence
                        seqClient.deleteSequence(outSequence, tenantDomain);
                    }

                    //If an outSequence has been added or updated.
                    if (isSequenceDefined(api.getOutSequence())){
                        //Deploy outSequence
                        deployOutSequence(api, tenantId, tenantDomain, environment);
                    }
                }
            } catch (Exception e) {
                String msg = "Error in updating the sequence at the Gateway";
                log.error(msg, e);
                throw new AppManagementException(msg, e);
            }
            finally {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }

    }

    private boolean isSequenceDefined(String sequence){
        if(sequence != null && !"none".equals(sequence)){
            return true;
        }
        return false;
    }

}
