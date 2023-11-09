/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.appframework.service;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.openmrs.Location;
import org.openmrs.LocationTag;
import org.openmrs.api.LocationService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.appframework.AppFrameworkActivator;
import org.openmrs.module.appframework.AppFrameworkConstants;
import org.openmrs.module.appframework.LoginLocationFilter;
import org.openmrs.module.appframework.config.AppFrameworkConfig;
import org.openmrs.module.appframework.context.AppContextModel;
import org.openmrs.module.appframework.domain.AppDescriptor;
import org.openmrs.module.appframework.domain.AppTemplate;
import org.openmrs.module.appframework.domain.ComponentState;
import org.openmrs.module.appframework.domain.ComponentType;
import org.openmrs.module.appframework.domain.Extension;
import org.openmrs.module.appframework.domain.Requireable;
import org.openmrs.module.appframework.domain.UserApp;
import org.openmrs.module.appframework.feature.FeatureToggleProperties;
import org.openmrs.module.appframework.repository.AllAppDescriptors;
import org.openmrs.module.appframework.repository.AllAppTemplates;
import org.openmrs.module.appframework.repository.AllComponentsState;
import org.openmrs.module.appframework.repository.AllFreeStandingExtensions;
import org.openmrs.module.appframework.repository.AllUserApps;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * It is a default implementation of {@link AppFrameworkService}.
 */
public class AppFrameworkServiceImpl extends BaseOpenmrsService implements AppFrameworkService {

	private final Log log = LogFactory.getLog(getClass());

	private AllAppTemplates allAppTemplates;

	private AllAppDescriptors allAppDescriptors;

	private AllFreeStandingExtensions allFreeStandingExtensions;

	private AllComponentsState allComponentsState;

	private LocationService locationService;

	private FeatureToggleProperties featureToggles;

	private AppFrameworkConfig appFrameworkConfig;

	private AllUserApps allUserApps;

	private org.graalvm.polyglot.Context context;

	public AppFrameworkServiceImpl(AllAppTemplates allAppTemplates, AllAppDescriptors allAppDescriptors,
	    AllFreeStandingExtensions allFreeStandingExtensions, AllComponentsState allComponentsState,
	    LocationService locationService, FeatureToggleProperties featureToggles, AppFrameworkConfig appFrameworkConfig,
	    AllUserApps allUserApps) {
		this.allAppTemplates = allAppTemplates;
		this.allAppDescriptors = allAppDescriptors;
		this.allFreeStandingExtensions = allFreeStandingExtensions;
		this.allComponentsState = allComponentsState;
		this.locationService = locationService;
		this.featureToggles = featureToggles;
		this.appFrameworkConfig = appFrameworkConfig;
		this.allUserApps = allUserApps;

		this.context = org.graalvm.polyglot.Context.newBuilder()
				.allowAllAccess(true)
				.allowExperimentalOptions(true).option("js.nashorn-compat", "true")
				.build();

		this.context.eval("js","function hasMemberWithProperty(list, propName, val) { " + "if (!list) { return false; } "
		        + "var i, len=list.length; " + "for (i=0; i<len; ++i) { "
		        + "  if (list[i][propName] == val) { return true; } " + "} " + "return false; " + "}");


		this.context.eval("js","function some(list, func) { " + "if (!list) { return false; } "
                + "var i, len=list.length; " + "for (i=0; i<len; ++i) { "
                + "  if (func(list[i]) === true) { return true; } " + "} " + "return false; " + "}");

		this.context.eval("js", "function fullMonthsBetweenDates(earlierDate, laterDate) { "
				+ "var d1 = new Date(earlierDate); "
				+ "var d2 = new Date(laterDate); "
				+ "var monthsBetween = ((d2.getFullYear() - d1.getFullYear()) * 12) + (d2.getMonth() - d1.getMonth()); "
				+ "if (d2.getDate() < d1.getDate()) { monthsBetween = monthsBetween - 1; } "
				+ "return monthsBetween; "
				+ "}");
	}

	@Override
	public List<AppDescriptor> getAllApps() {
		return allAppDescriptors.getAppDescriptors();
	}

	@Override
	public List<Extension> getExtensionsById(String extensionPointId, String id) {
		List<Extension> matchingExtensions = new ArrayList<Extension>();
		for (Extension extension : getAllExtensions(extensionPointId)) {
			if (extension.getId().equalsIgnoreCase(id)) {
				matchingExtensions.add(extension);
			}
		}
		return matchingExtensions;
	}

	@Override
	// TODO shouldn't this be getAllFreeStandingExtensions?
	public List<Extension> getAllExtensions(String extensionPointId) {
		List<Extension> matchingExtensions = new ArrayList<Extension>();
		for (Extension extension : allFreeStandingExtensions.getExtensions()) {
			if (extensionPointId == null || extension.getExtensionPointId().equalsIgnoreCase(extensionPointId))
				matchingExtensions.add(extension);
		}
		return matchingExtensions;
	}

	@Override
	public List<AppDescriptor> getAllEnabledApps() {

		// first just get all apps
		List<AppDescriptor> appDescriptors = getAllApps();

		// find out which ones are disabled
		List<AppDescriptor> disabledAppDescriptors = new ArrayList<AppDescriptor>();
		for (AppDescriptor appDescriptor : appDescriptors) {
			if (disabledByComponentState(appDescriptor) || disabledByFeatureToggle(appDescriptor)
			        || disabledByAppFrameworkConfig(appDescriptor)) {
				disabledAppDescriptors.add(appDescriptor);
			}
		}

		// remove disabled apps
		appDescriptors.removeAll(disabledAppDescriptors);
		return appDescriptors;
	}

	private boolean disabledByComponentState(AppDescriptor appDescriptor) {
		ComponentState componentState = allComponentsState.getComponentState(appDescriptor.getId(), ComponentType.APP);
		return componentState != null && !componentState.getEnabled();
	}

	private boolean disabledByFeatureToggle(AppDescriptor appDescriptor) {
		return disabledByFeatureToggle(appDescriptor.getFeatureToggle());
	}

	private boolean disabledByFeatureToggle(String featureToggle) {

		if (StringUtils.isBlank(featureToggle)) {
			return false;
		}

		Boolean negated = false;

		if (featureToggle.startsWith("!")) {
			featureToggle = featureToggle.substring(1);
			negated = true;
		}

		Boolean featureEnabled = featureToggles.isFeatureEnabled(featureToggle);

		return (!negated && !featureEnabled) || (negated && featureEnabled);
	}

	private boolean disabledByAppFrameworkConfig(AppDescriptor appDescriptor) {
		return !appFrameworkConfig.isEnabled(appDescriptor);
	}

	@Override
	public List<Extension> getAllEnabledExtensions() {
		List<Extension> extensions = new ArrayList<Extension>();

		// first get all extensions from enabled apps
		for (AppDescriptor app : getAllEnabledApps()) {
			if (app.getExtensions() != null) {
				for (Extension extension : app.getExtensions()) {
					// extensions that belong to apps can't be disabled independently of their app, so we don't check AllComponentsState here
					if (!disabledByFeatureToggle(extension) && !disabledByAppFrameworkConfig(extension)) {
						extensions.add(extension);
					}
				}
			}
		}

		// now get "standalone extensions"
		for (Extension extension : allFreeStandingExtensions.getExtensions()) {
			if (!disabledByFeatureToggle(extension) && !disabledByComponentState(extension)
			        && !disabledByAppFrameworkConfig(extension)) {
				extensions.add(extension);
			}
		}

		Collections.sort(extensions);
		return extensions;
	}

	/**
	 * @see org.openmrs.module.appframework.service.AppFrameworkService#getAllEnabledExtensions(java.lang.String)
	 */
	@Override
	public List<Extension> getAllEnabledExtensions(String extensionPointId) {
		List<Extension> extensions = new ArrayList<Extension>();

		// first get all extensions from enabled apps
		for (AppDescriptor app : getAllEnabledApps()) {
			if (app.getExtensions() != null) {
				for (Extension extension : app.getExtensions()) {
					// extensions that belong to apps can't be disabled independently of their app, so we don't check AllComponentsState here
					if (matchesExtensionPoint(extension, extensionPointId) && !disabledByFeatureToggle(extension)
					        && !disabledByAppFrameworkConfig(extension)) {
						extensions.add(extension);
					}
				}
			}
		}

		// now get "standalone extensions"
		for (Extension extension : allFreeStandingExtensions.getExtensions()) {
			if (matchesExtensionPoint(extension, extensionPointId) && !disabledByFeatureToggle(extension)
			        && !disabledByComponentState(extension) && !disabledByAppFrameworkConfig(extension)) {
				extensions.add(extension);
			}
		}

		Collections.sort(extensions);
		return extensions;
	}

	private boolean matchesExtensionPoint(Extension extension, String extensionPointId) {
		return extensionPointId == null || extensionPointId.equals(extension.getExtensionPointId());
	}

	private boolean disabledByComponentState(Extension extension) {
		ComponentState componentState = allComponentsState.getComponentState(extension.getId(), ComponentType.EXTENSION);
		return componentState != null && !componentState.getEnabled();
	}

	private boolean disabledByFeatureToggle(Extension extension) {
		return disabledByFeatureToggle(extension.getFeatureToggle());
	}

	private boolean disabledByAppFrameworkConfig(Extension extension) {
		return !appFrameworkConfig.isEnabled(extension);
	}

	@Override
	public void enableApp(String appId) {
		allComponentsState.setComponentState(appId, ComponentType.APP, true);
	}

	@Override
	public void disableApp(String appId) {
		allComponentsState.setComponentState(appId, ComponentType.APP, false);
	}

	@Override
	public void enableExtension(String extensionId) {
		allComponentsState.setComponentState(extensionId, ComponentType.EXTENSION, true);
	}

	@Override
	public void disableExtension(String extensionId) {
		allComponentsState.setComponentState(extensionId, ComponentType.EXTENSION, false);
	}

	@Override
	public List<Extension> getExtensionsForCurrentUser() {
		return getExtensionsForCurrentUser(null);
	}

	@Override
	public List<Extension> getExtensionsForCurrentUser(String extensionPointId) {
		return getExtensionsForCurrentUser(extensionPointId, null);
	}

	@Override
	public List<Extension> getExtensionsForCurrentUser(String extensionPointId, AppContextModel contextModel) {
		List<Extension> extensions = new ArrayList<Extension>();
		UserContext userContext = Context.getUserContext();

		for (Extension candidate : getAllEnabledExtensions(extensionPointId)) {
			if ((candidate.getBelongsTo() == null
			        || hasPrivilege(userContext, candidate.getBelongsTo().getRequiredPrivilege()))
			        && hasPrivilege(userContext, candidate.getRequiredPrivilege())) {
				if (contextModel == null || checkRequireExpression(candidate, contextModel)) {
					extensions.add(candidate);
				}
			}
		}

		return extensions;
	}

	@Override
	public boolean checkRequireExpression(Requireable candidate, AppContextModel contextModel) {
		try {
			String requireExpression = candidate.getRequire();
			for (Map.Entry<String, Object> e : contextModel.entrySet()) {
				String jsonValue = new ObjectMapper().writeValueAsString(e.getValue());
				context.eval("js","var " + e.getKey() + " = " + jsonValue);
			}
			return context.eval("js","(" + requireExpression + ") == true").asBoolean();
		}
		catch (Exception e) {
			log.error("Failed to evaluate 'require' check for extension " + candidate.getId(), e);
			return false;
		}
	}

	@Override
	public List<AppDescriptor> getAppsForCurrentUser() {
		List<AppDescriptor> userApps = new ArrayList<AppDescriptor>();
		UserContext userContext = Context.getUserContext();

		List<AppDescriptor> enabledApps = getAllEnabledApps();

		for (AppDescriptor candidate : enabledApps) {
			if (hasPrivilege(userContext, candidate.getRequiredPrivilege())) {
				userApps.add(candidate);
			}
		}
		return userApps;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Location> getLoginLocations() {
		LocationTag supportsLogin = locationService.getLocationTagByName(AppFrameworkConstants.LOCATION_TAG_SUPPORTS_LOGIN);
		List<Location> locations = locationService.getLocationsByTag(supportsLogin);
		List<LoginLocationFilter> filters = Context.getRegisteredComponents(LoginLocationFilter.class);
		if (filters.isEmpty()) {
			return locations;
		}

		List<Location> allowedLocations = new ArrayList();
		for (Location location : locations) {
			boolean exclude = false;
			for (LoginLocationFilter filter : filters) {
				if (!filter.accept(location)) {
					exclude = true;
					break;
				}
			}

			if (!exclude) {
				allowedLocations.add(location);
			}
		}

		return allowedLocations;
	}

	private boolean hasPrivilege(UserContext userContext, String privilege) {
		return StringUtils.isBlank(privilege) || userContext.hasPrivilege(privilege);
	}

	@Override
	public List<AppTemplate> getAllAppTemplates() {
		return allAppTemplates.getAppTemplates();
	}

	@Override
	public AppTemplate getAppTemplate(String id) {
		return allAppTemplates.getAppTemplate(id);
	}

	@Override
	public AppDescriptor getApp(String id) {
		return allAppDescriptors.getAppDescriptor(id);
	}

	@Override
	public UserApp getUserApp(String appId) {
		return allUserApps.getUserApp(appId);
	}

	@Override
	public List<UserApp> getUserApps() {
		return allUserApps.getUserApps();
	}

	@Override
	public UserApp saveUserApp(UserApp userApp) {
		UserApp toReturn = allUserApps.saveUserApp(userApp);
		//Refresh to pick up the newly added ones and any changes
		new AppFrameworkActivator().contextRefreshed();
		return toReturn;
	}

	@Override
	public void purgeUserApp(UserApp userApp) {
		allUserApps.deleteUserApp(userApp);
		new AppFrameworkActivator().contextRefreshed();
	}

}
