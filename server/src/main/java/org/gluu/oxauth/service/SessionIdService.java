/*
 * Janssen Project software is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.gluu.oxauth.service;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.apache.commons.lang.StringUtils;
import org.gluu.oxauth.audit.ApplicationAuditLogger;
import org.gluu.oxauth.model.audit.Action;
import org.gluu.oxauth.model.audit.OAuth2AuditLog;
import org.gluu.oxauth.model.authorize.AuthorizeRequestParam;
import org.gluu.oxauth.model.common.Prompt;
import org.gluu.oxauth.model.common.SessionId;
import org.gluu.oxauth.model.common.SessionIdState;
import org.gluu.oxauth.model.common.User;
import org.gluu.oxauth.model.config.Constants;
import org.gluu.oxauth.model.config.StaticConfiguration;
import org.gluu.oxauth.model.config.WebKeysConfiguration;
import org.gluu.oxauth.model.configuration.AppConfiguration;
import org.gluu.oxauth.model.crypto.signature.SignatureAlgorithm;
import org.gluu.oxauth.model.exception.AcrChangedException;
import org.gluu.oxauth.model.exception.InvalidSessionStateException;
import org.gluu.oxauth.model.jwt.Jwt;
import org.gluu.oxauth.model.jwt.JwtClaimName;
import org.gluu.oxauth.model.jwt.JwtSubClaimObject;
import org.gluu.oxauth.model.token.JwtSigner;
import org.gluu.oxauth.model.util.JwtUtil;
import org.gluu.oxauth.model.util.Pair;
import org.gluu.oxauth.model.util.Util;
import org.gluu.oxauth.security.Identity;
import org.gluu.oxauth.service.common.UserService;
import org.gluu.oxauth.service.external.ExternalApplicationSessionService;
import org.gluu.oxauth.service.external.ExternalAuthenticationService;
import org.gluu.oxauth.service.external.session.SessionEvent;
import org.gluu.oxauth.service.external.session.SessionEventType;
import org.gluu.oxauth.util.ServerUtil;
import io.jans.orm.PersistenceEntryManager;
import io.jans.orm.exception.EntryPersistenceException;
import io.jans.search.filter.Filter;
import io.jans.service.CacheService;
import io.jans.service.LocalCacheService;
import io.jans.util.StringHelper;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.slf4j.Logger;

import javax.enterprise.context.RequestScoped;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * @author Yuriy Zabrovarnyy
 * @author Yuriy Movchan
 * @author Javier Rojas Blum
 * @version December 8, 2018
 */
@RequestScoped
@Named
public class SessionIdService {

    public static final String OP_BROWSER_STATE = "opbs";
    public static final String SESSION_CUSTOM_STATE = "session_custom_state";
    private static final int MAX_MERGE_ATTEMPTS = 3;
    private static final int DEFAULT_LOCAL_CACHE_EXPIRATION = 2;

    @Inject
    private Logger log;

    @Inject
    private ExternalAuthenticationService externalAuthenticationService;

    @Inject
    private ExternalApplicationSessionService externalApplicationSessionService;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private WebKeysConfiguration webKeysConfiguration;

    @Inject
    private FacesContext facesContext;

    @Inject
    private ExternalContext externalContext;

    @Inject
    private RequestParameterService requestParameterService;

    @Inject
    private UserService userService;

    @Inject
    private PersistenceEntryManager persistenceEntryManager;

    @Inject
    private StaticConfiguration staticConfiguration;

    @Inject
    private CookieService cookieService;

    @Inject
    private Identity identity;

    @Inject
    private LocalCacheService localCacheService;

    @Inject
    private CacheService cacheService;

    private String buildDn(String sessionId) {
        return String.format("oxId=%s,%s", sessionId, staticConfiguration.getBaseDn().getSessions());
    }

    public Set<SessionId> getCurrentSessions() {
        final Set<String> ids = cookieService.getCurrentSessions();
        final Set<SessionId> sessions = Sets.newHashSet();
        for (String sessionId : ids) {
            if (StringUtils.isBlank(sessionId)) {
                log.error("Invalid sessionId in current_sessions: " + sessionId);
                continue;
            }

            final SessionId sessionIdObj = getSessionId(sessionId);
            if (sessionIdObj == null) {
                log.trace("Unable to find session object by id: " + sessionId + " {expired?}");
                continue;
            }

            if (sessionIdObj.getState() != SessionIdState.AUTHENTICATED) {
                log.error("Session is not authenticated, id: " + sessionId);
                continue;
            }
            sessions.add(sessionIdObj);
        }
        return sessions;
    }

    public String getAcr(SessionId session) {
        if (session == null) {
            return null;
        }

        String acr = session.getSessionAttributes().get(JwtClaimName.AUTHENTICATION_CONTEXT_CLASS_REFERENCE);
        if (StringUtils.isBlank(acr)) {
            acr = session.getSessionAttributes().get("acr_values");
        }
        return acr;
    }

    // #34 - update session attributes with each request
    // 1) redirect_uri change -> update session
    // 2) acr change -> throw acr change exception
    // 3) client_id change -> do nothing
    // https://github.com/GluuFederation/oxAuth/issues/34
    public SessionId assertAuthenticatedSessionCorrespondsToNewRequest(SessionId session, String acrValuesStr) throws AcrChangedException {
        if (session != null && !session.getSessionAttributes().isEmpty() && session.getState() == SessionIdState.AUTHENTICATED) {

            final Map<String, String> sessionAttributes = session.getSessionAttributes();

            String sessionAcr = getAcr(session);

            if (StringUtils.isBlank(sessionAcr)) {
                log.trace("Failed to fetch acr from session, attributes: " + sessionAttributes);
                return session;
            }

            List<String> acrValuesList = acrValuesList(acrValuesStr);
            boolean isAcrChanged = !acrValuesList.isEmpty() && !acrValuesList.contains(sessionAcr);
            if (isAcrChanged) {
                Map<String, Integer> acrToLevel = externalAuthenticationService.acrToLevelMapping();
                Integer sessionAcrLevel = acrToLevel.get(externalAuthenticationService.scriptName(sessionAcr));

                for (String acrValue : acrValuesList) {
                    Integer currentAcrLevel = acrToLevel.get(externalAuthenticationService.scriptName(acrValue));

                    log.info("Acr is changed. Session acr: " + sessionAcr + "(level: " + sessionAcrLevel + "), " +
                            "current acr: " + acrValue + "(level: " + currentAcrLevel + ")");

                    // Requested acr method not enabled
                    if (currentAcrLevel == null) {
                        throw new AcrChangedException(false);
                    }

                    if (sessionAcrLevel < currentAcrLevel) {
                        throw new AcrChangedException();
                    }
                }
                // https://github.com/GluuFederation/oxAuth/issues/291
                return session; // we don't want to reinit login because we have stronger acr (avoid overriding)
            }

            reinitLogin(session, false);
        }

        return session;
    }

    private static boolean shouldReinitSession(Map<String, String> sessionAttributes, Map<String, String> currentSessionAttributes) {
        final Map<String, String> copySessionAttributes = new HashMap<>(sessionAttributes);
        final Map<String, String> copyCurrentSessionAttributes = new HashMap<>(currentSessionAttributes);

        // it's up to RP whether to change state per request
        copySessionAttributes.remove(AuthorizeRequestParam.STATE);
        copyCurrentSessionAttributes.remove(AuthorizeRequestParam.STATE);

        return !copyCurrentSessionAttributes.equals(copySessionAttributes);
    }

    /**
     *
     * @param session
     * @param force
     * @return returns whether session was updated
     */
    public boolean reinitLogin(SessionId session, boolean force) {
        final Map<String, String> sessionAttributes = session.getSessionAttributes();
        final Map<String, String> currentSessionAttributes = getCurrentSessionAttributes(sessionAttributes);

        if (force || shouldReinitSession(sessionAttributes, currentSessionAttributes)) {
            sessionAttributes.putAll(currentSessionAttributes);

            // Reinit login
            sessionAttributes.put("c", "1");

            for (Iterator<Entry<String, String>> it = currentSessionAttributes.entrySet().iterator(); it.hasNext(); ) {
                Entry<String, String> currentSessionAttributesEntry = it.next();
                String name = currentSessionAttributesEntry.getKey();
                if (name.startsWith("auth_step_passed_")) {
                    it.remove();
                }
            }

            session.setSessionAttributes(currentSessionAttributes);

            if (force) {
            	// Reset state to unauthenticated
            	session.setState(SessionIdState.UNAUTHENTICATED);
                externalEvent(new SessionEvent(SessionEventType.UNAUTHENTICATED, session));
            }

            boolean updateResult = updateSessionId(session, true, true, true);
            if (!updateResult) {
                log.debug("Failed to update session entry: '{}'", session.getId());
            }
            return updateResult;
        }
        return false;
    }

    public SessionId resetToStep(SessionId session, int resetToStep) {
        final Map<String, String> sessionAttributes = session.getSessionAttributes();

        int currentStep = 1;
        if (sessionAttributes.containsKey("auth_step")) {
            currentStep = StringHelper.toInteger(sessionAttributes.get("auth_step"), currentStep);
        }

        for (int i = resetToStep; i <= currentStep; i++) {
            String key = String.format("auth_step_passed_%d", i);
            sessionAttributes.remove(key);
        }

        sessionAttributes.put("auth_step", String.valueOf(resetToStep));

        boolean updateResult = updateSessionId(session, true, true, true);
        if (!updateResult) {
            log.debug("Failed to update session entry: '{}'", session.getId());
            return null;
        }
        
        return session;
    }

    private Map<String, String> getCurrentSessionAttributes(Map<String, String> sessionAttributes) {
        if (facesContext == null) {
            return sessionAttributes;
        }

        // Update from request
        final Map<String, String> currentSessionAttributes = new HashMap<>(sessionAttributes);

        Map<String, String> parameterMap = externalContext.getRequestParameterMap();
        Map<String, String> newRequestParameterMap = requestParameterService.getAllowedParameters(parameterMap);
        for (Entry<String, String> newRequestParameterMapEntry : newRequestParameterMap.entrySet()) {
            String name = newRequestParameterMapEntry.getKey();
            if (!StringHelper.equalsIgnoreCase(name, "auth_step")) {
                currentSessionAttributes.put(name, newRequestParameterMapEntry.getValue());
            }
        }

        return currentSessionAttributes;
    }

    public SessionId getSessionId() {
        String sessionId = cookieService.getSessionIdFromCookie();

        if (StringHelper.isEmpty(sessionId)) {
        	if (identity.getSessionId() != null) {
        		sessionId = identity.getSessionId().getId();
        	}
        }

        if (StringHelper.isNotEmpty(sessionId)) {
            return getSessionId(sessionId);
        } else {
        	log.trace("Session cookie not exists");
        }

        return null;
    }

    public Map<String, String> getSessionAttributes(SessionId sessionId) {
        if (sessionId != null) {
            return sessionId.getSessionAttributes();
        }

        return null;
    }

    public SessionId generateAuthenticatedSessionId(HttpServletRequest httpRequest, String userDn) throws InvalidSessionStateException {
        Map<String, String> sessionIdAttributes = new HashMap<>();
        sessionIdAttributes.put("prompt", "");

        return generateAuthenticatedSessionId(httpRequest, userDn, sessionIdAttributes);
    }

    public SessionId generateAuthenticatedSessionId(HttpServletRequest httpRequest, String userDn, String prompt) throws InvalidSessionStateException {
        Map<String, String> sessionIdAttributes = new HashMap<>();
        sessionIdAttributes.put("prompt", prompt);

        return generateAuthenticatedSessionId(httpRequest, userDn, sessionIdAttributes);
    }

    public SessionId generateAuthenticatedSessionId(HttpServletRequest httpRequest, String userDn, Map<String, String> sessionIdAttributes) throws InvalidSessionStateException {
        SessionId sessionId = generateSessionId(userDn, new Date(), SessionIdState.AUTHENTICATED, sessionIdAttributes, true);

        if (externalApplicationSessionService.isEnabled()) {
            String userName = sessionId.getSessionAttributes().get(Constants.AUTHENTICATED_USER);
            boolean externalResult = externalApplicationSessionService.executeExternalStartSessionMethods(httpRequest, sessionId);
            log.info("Start session result for '{}': '{}'", userName, "start", externalResult);

            if (!externalResult) {
            	reinitLogin(sessionId, true);
            	throw new InvalidSessionStateException("Session creation is prohibited by external session script!");
            }

            externalEvent(new SessionEvent(SessionEventType.AUTHENTICATED, sessionId).setHttpRequest(httpRequest));
        }

        return sessionId;
    }

    public SessionId generateUnauthenticatedSessionId(String userDn) {
        Map<String, String> sessionIdAttributes = new HashMap<>();
        return generateSessionId(userDn, new Date(), SessionIdState.UNAUTHENTICATED, sessionIdAttributes, true);
    }

    public SessionId generateUnauthenticatedSessionId(String userDn, Date authenticationDate, SessionIdState state, Map<String, String> sessionIdAttributes, boolean persist) {
        return generateSessionId(userDn, authenticationDate, state, sessionIdAttributes, persist);
    }
    
    public String computeSessionState(SessionId sessionId, String clientId, String redirectUri) {
        final boolean isSameClient = clientId.equals(sessionId.getSessionAttributes().get("client_id")) &&
                redirectUri.equals(sessionId.getSessionAttributes().get("redirect_uri"));
        if(isSameClient)
            return sessionId.getSessionState();
        final String salt = UUID.randomUUID().toString();
        final String opbs = sessionId.getOPBrowserState();
        final String sessionState = computeSessionState(clientId,redirectUri, opbs, salt);
        return sessionState;
    }

    private String computeSessionState(String clientId, String redirectUri, String opbs, String salt) {
        try {
            final String clientOrigin = getClientOrigin(redirectUri);
            final String sessionState = JwtUtil.bytesToHex(JwtUtil.getMessageDigestSHA256(
                    clientId + " " + clientOrigin + " " + opbs + " " + salt)) + "." + salt;
            return sessionState;
        } catch (NoSuchProviderException | NoSuchAlgorithmException | UnsupportedEncodingException | URISyntaxException e) {
            log.error("Failed generating session state! " + e.getMessage(), e);
            throw new RuntimeException(e);
		}
    }

    private String getClientOrigin(String redirectUri) throws URISyntaxException {
    	if (StringHelper.isNotEmpty(redirectUri)) {
	        final URI uri = new URI(redirectUri);
	        String result = uri.getScheme() + "://" + uri.getHost();
	        if(uri.getPort() > 0)
	            result += ":" + Integer.toString(uri.getPort());
	        return result;
    	} else {
    		return appConfiguration.getIssuer();
    	}
    }

    private SessionId generateSessionId(String userDn, Date authenticationDate, SessionIdState state, Map<String, String> sessionIdAttributes, boolean persist) {
        final String internalSid = UUID.randomUUID().toString();
        final String outsideSid = UUID.randomUUID().toString();
        final String salt = UUID.randomUUID().toString();
        final String clientId = sessionIdAttributes.get("client_id");
        final String opbs = UUID.randomUUID().toString();
        final String redirectUri = sessionIdAttributes.get("redirect_uri");
        final String sessionState = computeSessionState(clientId, redirectUri, opbs, salt);
        final String dn = buildDn(internalSid);
        sessionIdAttributes.put(OP_BROWSER_STATE, opbs);

        Preconditions.checkNotNull(dn);

        if (SessionIdState.AUTHENTICATED == state && StringUtils.isBlank(userDn) && !sessionIdAttributes.containsKey("uma")) {
            return null;
        }

        final SessionId sessionId = new SessionId();
        sessionId.setId(internalSid);
        sessionId.setOutsideSid(outsideSid);
        sessionId.setDn(dn);
        sessionId.setUserDn(userDn);
        sessionId.setSessionState(sessionState);

        final Pair<Date, Integer> expiration = expirationDate(sessionId.getCreationDate(), state);
        sessionId.setExpirationDate(expiration.getFirst());
        sessionId.setTtl(expiration.getSecond());

        Boolean sessionAsJwt = appConfiguration.getSessionAsJwt();
        sessionId.setIsJwt(sessionAsJwt != null && sessionAsJwt);

        sessionId.setAuthenticationTime(authenticationDate != null ? authenticationDate : new Date());

        if (state != null) {
            sessionId.setState(state);
        }

        sessionId.setSessionAttributes(sessionIdAttributes);
        sessionId.setLastUsedAt(new Date());

        if (sessionId.getIsJwt()) {
            sessionId.setJwt(generateJwt(sessionId, userDn).asString());
        }

        boolean persisted = false;
        if (persist) {
            persisted = persistSessionId(sessionId);
        }

        auditLogging(sessionId);

        log.trace("Generated new session, id = '{}', state = '{}', asJwt = '{}', persisted = '{}'", sessionId.getId(), sessionId.getState(), sessionId.getIsJwt(), persisted);
        return sessionId;
    }


    private Jwt generateJwt(SessionId sessionId, String audience) {
        try {
            JwtSigner jwtSigner = new JwtSigner(appConfiguration, webKeysConfiguration, SignatureAlgorithm.RS512, audience);
            Jwt jwt = jwtSigner.newJwt();

            // claims
            jwt.getClaims().setClaim("id", sessionId.getId());
            jwt.getClaims().setClaim("authentication_time", sessionId.getAuthenticationTime());
            jwt.getClaims().setClaim("user_dn", sessionId.getUserDn());
            jwt.getClaims().setClaim("state", sessionId.getState() != null ?
                    sessionId.getState().getValue() : "");

            jwt.getClaims().setClaim("session_attributes", JwtSubClaimObject.fromMap(sessionId.getSessionAttributes()));

            jwt.getClaims().setClaim("last_used_at", sessionId.getLastUsedAt());
            jwt.getClaims().setClaim("permission_granted", sessionId.getPermissionGranted());
            jwt.getClaims().setClaim("permission_granted_map", JwtSubClaimObject.fromBooleanMap(sessionId.getPermissionGrantedMap().getPermissionGranted()));

            // sign
            return jwtSigner.sign();
        } catch (Exception e) {
            log.error("Failed to sign session jwt! " + e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public SessionId setSessionIdStateAuthenticated(HttpServletRequest httpRequest, HttpServletResponse httpResponse, SessionId sessionId, String p_userDn) {
        sessionId.setUserDn(p_userDn);
        sessionId.setAuthenticationTime(new Date());
        sessionId.setState(SessionIdState.AUTHENTICATED);

        final boolean persisted;
        if (appConfiguration.getChangeSessionIdOnAuthentication() && httpResponse != null) {
            final String oldSessionId = sessionId.getId();
            final String newSessionId = UUID.randomUUID().toString();

            log.debug("Changing session id from {} to {} ...", oldSessionId, newSessionId);
            remove(sessionId);

            sessionId.setId(newSessionId);
            sessionId.setDn(buildDn(newSessionId));
            sessionId.getSessionAttributes().put(SessionId.OLD_SESSION_ID_ATTR_KEY, oldSessionId);
            if (sessionId.getIsJwt()) {
                sessionId.setJwt(generateJwt(sessionId, sessionId.getUserDn()).asString());
            }

            persisted = persistSessionId(sessionId, true);
            cookieService.createSessionIdCookie(sessionId, httpRequest, httpResponse, false);
            log.debug("Session identifier changed from {} to {} .", oldSessionId, newSessionId);
        } else {
            persisted = updateSessionId(sessionId, true, true, true);
        }

        auditLogging(sessionId);
        log.trace("Authenticated session, id = '{}', state = '{}', persisted = '{}'", sessionId.getId(), sessionId.getState(), persisted);

        if (externalApplicationSessionService.isEnabled()) {
            String userName = sessionId.getSessionAttributes().get(Constants.AUTHENTICATED_USER);
            boolean externalResult = externalApplicationSessionService.executeExternalStartSessionMethods(httpRequest, sessionId);
            log.info("Start session result for '{}': '{}'", userName, "start", externalResult);

            if (!externalResult) {
            	reinitLogin(sessionId, true);
            	throw new InvalidSessionStateException("Session creation is prohibited by external session script!");
            }
            externalEvent(new SessionEvent(SessionEventType.AUTHENTICATED, sessionId).setHttpRequest(httpRequest).setHttpResponse(httpResponse));
        }

        return sessionId;
    }

    public boolean persistSessionId(final SessionId sessionId) {
        return persistSessionId(sessionId, false);
    }

    public boolean persistSessionId(final SessionId sessionId, boolean forcePersistence) {
        List<Prompt> prompts = getPromptsFromSessionId(sessionId);

        try {
            final int unusedLifetime = appConfiguration.getSessionIdUnusedLifetime();
            if ((unusedLifetime > 0 && isPersisted(prompts)) || forcePersistence) {
                sessionId.setLastUsedAt(new Date());

                final Pair<Date, Integer> expiration = expirationDate(sessionId.getCreationDate(), sessionId.getState());
                sessionId.setPersisted(true);
                sessionId.setExpirationDate(expiration.getFirst());
                sessionId.setTtl(expiration.getSecond());
                log.trace("sessionIdAttributes: " + sessionId.getPermissionGrantedMap());
                if (appConfiguration.getSessionIdPersistInCache()) {
                    cacheService.put(expiration.getSecond(), sessionId.getDn(), sessionId);
                } else {
                    persistenceEntryManager.persist(sessionId);
                }
                localCacheService.put(DEFAULT_LOCAL_CACHE_EXPIRATION, sessionId.getDn(), sessionId);
                return true;
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    public boolean updateSessionId(final SessionId sessionId) {
        return updateSessionId(sessionId, true);
    }

    public boolean updateSessionId(final SessionId sessionId, boolean updateLastUsedAt) {
        return updateSessionId(sessionId, updateLastUsedAt, false, true);
    }

    public boolean updateSessionId(final SessionId sessionId, boolean updateLastUsedAt, boolean forceUpdate, boolean modified) {
        List<Prompt> prompts = getPromptsFromSessionId(sessionId);

        try {
            final int unusedLifetime = appConfiguration.getSessionIdUnusedLifetime();
            if ((unusedLifetime > 0 && isPersisted(prompts)) || forceUpdate) {
                boolean update = modified;

                if (updateLastUsedAt) {
                    Date lastUsedAt = new Date();
                    if (sessionId.getLastUsedAt() != null) {
                        long diff = lastUsedAt.getTime() - sessionId.getLastUsedAt().getTime();
                        int unusedDiffInSeconds = (int) (diff/1000);
                        if (unusedDiffInSeconds > unusedLifetime) {
                            log.debug("Session id expired: {} by sessionIdUnusedLifetime, remove it.", sessionId.getId());
                            remove(sessionId); // expired
                            return false;
                        }

                        if (diff > 500) { // update only if diff is more than 500ms
                            update = true;
                            sessionId.setLastUsedAt(lastUsedAt);
                        }
                    } else {
                        update = true;
                        sessionId.setLastUsedAt(lastUsedAt);
                    }
                }

                if (!sessionId.isPersisted()) {
                    update = true;
                    sessionId.setPersisted(true);
                }

                if (isExpired(sessionId)) {
                    log.debug("Session id expired: {} by lifetime property, remove it.", sessionId.getId());
                    remove(sessionId); // expired
                    update = false;
                }

                if (update) {
                    mergeWithRetry(sessionId);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    public boolean isExpired(SessionId sessionId) {
        if (sessionId.getAuthenticationTime() == null) {
            return false;
        }
        final long currentLifetimeInSeconds = (System.currentTimeMillis() - sessionId.getAuthenticationTime().getTime()) / 1000;

        return currentLifetimeInSeconds > getServerSessionIdLifetimeInSeconds();
    }

    public int getServerSessionIdLifetimeInSeconds() {
        if (appConfiguration.getServerSessionIdLifetime() != null && appConfiguration.getServerSessionIdLifetime() > 0) {
            return appConfiguration.getServerSessionIdLifetime();
        }
        if (appConfiguration.getSessionIdLifetime() != null && appConfiguration.getSessionIdLifetime() > 0) {
            return appConfiguration.getSessionIdLifetime();
        }

        // we don't know for how long we can put it in cache/persistence since expiration is not set, so we set it to max integer.
        if (appConfiguration.getServerSessionIdLifetime() != null && appConfiguration.getSessionIdLifetime() != null &&
                appConfiguration.getServerSessionIdLifetime() <= 0 && appConfiguration.getSessionIdLifetime() <= 0) {
            return Integer.MAX_VALUE;
        }
        log.debug("Session id lifetime configuration is null.");
        return AppConfiguration.DEFAULT_SESSION_ID_LIFETIME;
    }

    private Pair<Date, Integer> expirationDate(Date creationDate, SessionIdState state) {
        int expirationInSeconds = state == SessionIdState.UNAUTHENTICATED ?
                appConfiguration.getSessionIdUnauthenticatedUnusedLifetime() :
                getServerSessionIdLifetimeInSeconds();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(creationDate);
        calendar.add(Calendar.SECOND, expirationInSeconds);
        return new Pair<>(calendar.getTime(), expirationInSeconds);
    }

    private void mergeWithRetry(final SessionId sessionId) {
        final Pair<Date, Integer> expiration = expirationDate(sessionId.getCreationDate(), sessionId.getState());
        sessionId.setExpirationDate(expiration.getFirst());
        sessionId.setTtl(expiration.getSecond());

        EntryPersistenceException lastException = null;
        for (int i = 1; i <= MAX_MERGE_ATTEMPTS; i++) {
            try {
                if (appConfiguration.getSessionIdPersistInCache()) {
                    cacheService.put(expiration.getSecond(), sessionId.getDn(), sessionId);
                } else {
                    persistenceEntryManager.merge(sessionId);
                }
                localCacheService.put(DEFAULT_LOCAL_CACHE_EXPIRATION, sessionId.getDn(), sessionId);
                externalEvent(new SessionEvent(SessionEventType.UPDATED, sessionId));
                return;
            } catch (EntryPersistenceException ex) {
                lastException = ex;
                if (ex.getCause() instanceof LDAPException) {
                    LDAPException parentEx = ((LDAPException) ex.getCause());
                    log.debug("LDAP exception resultCode: '{}'", parentEx.getResultCode().intValue());
                    if ((parentEx.getResultCode().intValue() == ResultCode.NO_SUCH_ATTRIBUTE_INT_VALUE) ||
                            (parentEx.getResultCode().intValue() == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS_INT_VALUE)) {
                        log.warn("Session entry update attempt '{}' was unsuccessfull", i);
                        continue;
                    }
                }

                throw ex;
            }
        }

        log.error("Session entry update attempt was unsuccessfull after '{}' attempts", MAX_MERGE_ATTEMPTS);
        throw lastException;
    }

    public void updateSessionIdIfNeeded(SessionId sessionId, boolean modified) {
        updateSessionId(sessionId, true, false, modified);
    }

    private boolean isPersisted(List<Prompt> prompts) {
        if (prompts != null && prompts.contains(Prompt.NONE)) {
            final Boolean persistOnPromptNone = appConfiguration.getSessionIdPersistOnPromptNone();
            return persistOnPromptNone != null && persistOnPromptNone;
        }
        return true;
    }

    @Nullable
    public SessionId getSessionById(@Nullable String sessionId, boolean silently) {
        return getSessionByDn(buildDn(sessionId), silently);
    }

    @Nullable
    public SessionId getSessionByDn(@Nullable String dn) {
        return getSessionByDn(dn, false);
    }

    @Nullable
    public SessionId getSessionByDn(@Nullable String dn, boolean silently) {
        if (StringUtils.isBlank(dn)) {
            return null;
        }

        final Object localCopy = localCacheService.get(dn);
        if (localCopy instanceof SessionId) {
            if (isSessionValid((SessionId) localCopy)) {
                return (SessionId) localCopy;
            } else {
                localCacheService.remove(dn);
            }
        }

        try {
            final SessionId sessionId;
            if (appConfiguration.getSessionIdPersistInCache()) {
                sessionId = (SessionId) cacheService.get(dn);
            } else {
                sessionId = persistenceEntryManager.find(SessionId.class, dn);
            }
            localCacheService.put(DEFAULT_LOCAL_CACHE_EXPIRATION, sessionId.getDn(), sessionId);
            return sessionId;
        } catch (Exception e) {
            if (!silently) {
                log.error("Failed to get session by dn: " + dn, e);
            }
        }
        return null;
    }

    @Deprecated
    public String getSessionIdFromCookie() {
        return cookieService.getSessionIdFromCookie();
    }

    public SessionId getSessionId(HttpServletRequest request) {
        final String sessionIdFromCookie = cookieService.getSessionIdFromCookie(request);
        log.trace("SessionId from cookie: " + sessionIdFromCookie);
        return getSessionId(sessionIdFromCookie);
    }

    public SessionId getSessionId(String sessionId) {
        return getSessionId(sessionId, false);
    }

    public SessionId getSessionId(String sessionId, boolean silently) {
        if (StringHelper.isEmpty(sessionId)) {
            return null;
        }

        try {
            final SessionId entity = getSessionById(sessionId, silently);
            log.trace("Try to get session by id: {} ...", sessionId);
            if (entity != null) {
                log.trace("Session dn: {}", entity.getDn());

                if (isSessionValid(entity)) {
                    return entity;
                }
            }
        } catch (Exception ex) {
            if (!silently) {
                log.trace(ex.getMessage(), ex);
            }
        }

        log.trace("Failed to get session by id: {}", sessionId);
        return null;
    }

    public boolean remove(SessionId sessionId) {
        try {
            if (appConfiguration.getSessionIdPersistInCache()) {
                cacheService.remove(sessionId.getDn());
            } else {
                persistenceEntryManager.remove(sessionId.getDn());
            }
            localCacheService.remove(sessionId.getDn());
            externalEvent(new SessionEvent(SessionEventType.GONE, sessionId));
            return true;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }

    public void remove(List<SessionId> list) {
        for (SessionId id : list) {
            try {
                remove(id);
            } catch (Exception e) {
                log.error("Failed to remove entry", e);
            }
        }
    }

    public boolean isSessionValid(SessionId sessionId) {
        if (sessionId == null) {
            return false;
        }

        final long sessionInterval = TimeUnit.SECONDS.toMillis(appConfiguration.getSessionIdUnusedLifetime());
        final long sessionUnauthenticatedInterval = TimeUnit.SECONDS.toMillis(appConfiguration.getSessionIdUnauthenticatedUnusedLifetime());

        final long timeSinceLastAccess = System.currentTimeMillis() - sessionId.getLastUsedAt().getTime();
        if (timeSinceLastAccess > sessionInterval && appConfiguration.getSessionIdUnusedLifetime() != -1) {
            return false;
        }
        if (sessionId.getState() == SessionIdState.UNAUTHENTICATED && timeSinceLastAccess > sessionUnauthenticatedInterval && appConfiguration.getSessionIdUnauthenticatedUnusedLifetime() != -1) {
            return false;
        }

        return true;
    }

    private List<Prompt> getPromptsFromSessionId(final SessionId sessionId) {
        String promptParam = sessionId.getSessionAttributes().get("prompt");
        return Prompt.fromString(promptParam, " ");
    }

    public boolean isSessionIdAuthenticated(SessionId sessionId) {
        if (sessionId == null) {
            return false;
        }
        return SessionIdState.AUTHENTICATED.equals(sessionId.getState());
    }

    /**
     * By definition we expects space separated acr values as it is defined in spec. But we also try maybe some client
     * sent it to us as json array. So we try both.
     *
     * @return acr value list
     */
    public List<String> acrValuesList(String acrValues) {
        List<String> acrs;
        try {
            acrs = Util.jsonArrayStringAsList(acrValues);
        } catch (JSONException ex) {
            acrs = Util.splittedStringAsList(acrValues, " ");
        }

        return acrs;
    }

    private void auditLogging(SessionId sessionId) {
        HttpServletRequest httpServletRequest = ServerUtil.getRequestOrNull();
        if (httpServletRequest != null) {
            Action action;
            switch (sessionId.getState()) {
                case AUTHENTICATED:
                    action = Action.SESSION_AUTHENTICATED;
                    break;
                case UNAUTHENTICATED:
                    action = Action.SESSION_UNAUTHENTICATED;
                    break;
                default:
                    action = Action.SESSION_UNAUTHENTICATED;
            }
            OAuth2AuditLog oAuth2AuditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(httpServletRequest), action);
            oAuth2AuditLog.setSuccess(true);
            applicationAuditLogger.sendMessage(oAuth2AuditLog);
        }
    }

    public User getUser(SessionId sessionId) {
        if (sessionId == null) {
            return null;
        }

        if (sessionId.getUser() != null) {
            return sessionId.getUser();
        }

        if (StringUtils.isBlank(sessionId.getUserDn())) {
            return null;
        }

        final User user = userService.getUserByDn(sessionId.getUserDn());
        if (user != null) {
            sessionId.setUser(user);
            return user;
        }

        return null;
    }

    public List<SessionId> findByUser(String userDn) {
        if (appConfiguration.getSessionIdPersistInCache()) {
            throw new UnsupportedOperationException("Operation is not supported with sessionIdPersistInCache=true. Set it to false to avoid this exception.");
        }
        Filter filter = Filter.createEqualityFilter("oxAuthUserDN", userDn);
        return persistenceEntryManager.findEntries(staticConfiguration.getBaseDn().getSessions(), SessionId.class, filter);
    }

    public void externalEvent(SessionEvent event) {
        externalApplicationSessionService.externalEvent(event);
    }
}