/*
 * Janssen Project software is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.servlet;

import io.jans.as.common.service.OrganizationService;
import io.jans.as.persistence.model.GluuOrganization;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.Date;
@WebServlet(urlPatterns = "/servlet/favicon")
public class FaviconServlet extends HttpServlet {

	@Inject
	private OrganizationService organizationService;

	private static final long serialVersionUID = 5445488800130871634L;

	private static final Logger log = LoggerFactory.getLogger(FaviconServlet.class);
	public static final String BASE_OXAUTH_FAVICON_PATH = "/opt/gluu/jetty/oxauth/custom/static/favicon/";

	@Override
	protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse response)
			throws ServletException, IOException {
		response.setContentType("image/x-icon");
		response.setDateHeader("Expires", new Date().getTime()+1000L*1800);
		GluuOrganization organization = organizationService.getOrganization();
		boolean hasSucceed = readCustomFavicon(response, organization);
		if (!hasSucceed) {
			readDefaultFavicon(response);
		}
	}

	private boolean readDefaultFavicon(HttpServletResponse response) {
		String defaultFaviconFileName = "/WEB-INF/static/favicon.ico";
		try (InputStream in = getServletContext().getResourceAsStream(defaultFaviconFileName);
				OutputStream out = response.getOutputStream()) {
			IOUtils.copy(in, out);
			return true;
		} catch (IOException e) {
			log.debug("Error loading default favicon: " + e.getMessage());
			return false;
		}
	}

	private boolean readCustomFavicon(HttpServletResponse response, GluuOrganization organization) {
		if (organization.getJsFaviconPath() == null || StringUtils.isEmpty(organization.getJsFaviconPath())) {
			return false;
		}

		File directory = new File(BASE_OXAUTH_FAVICON_PATH);
		if (!directory.exists()) {
			directory.mkdir();
		}
		File faviconPath = new File(organization.getJsFaviconPath());
		if (!faviconPath.exists()) {
			return false;
		}
		try (InputStream in = new FileInputStream(faviconPath); OutputStream out = response.getOutputStream()) {
			IOUtils.copy(in, out);
			return true;
		} catch (IOException e) {
			log.debug("Error loading custom favicon: " + e.getMessage());
			return false;
		}
	}
}