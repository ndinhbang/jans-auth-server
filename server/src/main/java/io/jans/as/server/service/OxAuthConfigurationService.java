/*
 * Janssen Project software is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.service;

import io.jans.as.model.configuration.AppConfiguration;
import io.jans.util.StringHelper;

import javax.enterprise.context.ApplicationScoped;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletContext;

/**
 * OxAuthConfigurationService
 *
 * @author Oleksiy Tataryn Date: 08.07.2014
 */
@ApplicationScoped
@Named
@Deprecated //TODO: We don't need this class
public class OxAuthConfigurationService {

	@Inject
	private AppConfiguration appConfiguration;
	
	@Inject
	private ServletContext context;

	public String getCssLocation() {
		if (StringHelper.isEmpty(appConfiguration.getCssLocation())) {
			FacesContext ctx = FacesContext.getCurrentInstance();
			if (ctx == null) {
				return "";
			}
			String contextPath = ctx.getExternalContext().getRequestContextPath();
			return contextPath + "/stylesheet";
		} else {
			return appConfiguration.getCssLocation();
		}
	}

	public String getJsLocation() {
		if (StringHelper.isEmpty(appConfiguration.getJsLocation())) {
			String contextPath = context.getContextPath();
			return contextPath + "/js";
		} else {
			return appConfiguration.getJsLocation();
		}
	}

	public String getImgLocation() {
		if (StringHelper.isEmpty(appConfiguration.getImgLocation())) {
			String contextPath = context.getContextPath();
			return contextPath + "/img";
		} else {
			return appConfiguration.getImgLocation();
		}
	}

}