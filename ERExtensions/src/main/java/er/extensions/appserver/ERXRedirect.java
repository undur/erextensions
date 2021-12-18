package er.extensions.appserver;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver.WOSession;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableDictionary;

import er.extensions.appserver.ajax.ERXAjaxApplication;
import er.extensions.foundation.ERXMutableURL;

/**
 * ERXRedirect is like a WORedirect except that you can give it a
 * component instance to redirect to (as well as several other convenient
 * methods of redirecting). This is useful for situations like in an Ajax
 * request where you want to do a full page reload that points to the component
 * that you would normally return from your action method. If your redirect is
 * in an Ajax request, this will generate a script tag that reassigns
 * document.location.href to the generated url.
 *
 * @author mschrag
 */

public class ERXRedirect extends WOComponent {

	/**
	 * Do I need to update serialVersionUID?
	 * See section 5.6 <cite>Type Changes Affecting Serialization</cite> on page 51 of the 
	 * <a href="http://java.sun.com/j2se/1.4/pdf/serial-spec.pdf">Java Object Serialization Spec</a>
	 */
	private static final long serialVersionUID = 1L;

	private String _url;
	private String _requestHandlerKey;
	private String _requestHandlerPath;
	private Boolean _secure;
	private boolean _includeSessionID;

	private String _directActionClass;
	private String _directActionName;
	private WOComponent _originalComponent;
	private WOComponent _component;
	private NSDictionary<String, ? extends Object> _queryParameters;

	public ERXRedirect(WOContext context) {
		super(context);
		_originalComponent = context.page();
		_includeSessionID = false;
	}

	/**
	 * Sets whether or not a secure URL should be generated. This does not apply
	 * if you set a URL directly.
	 * 
	 * @param secure
	 *            whether or not a secure URL should be generated
	 */
	public void setSecure(boolean secure) {
		_secure = Boolean.valueOf(secure);
	}
	
	/**
	 * Sets whether or not a direct action URL should contain the session ID.
	 * This defaults to <code>false</code> to maintain backward compatibility.
	 *  
	 * @param includeSessionID
	 *            whether or not a sessionID should be included
	 */
	public void setIncludeSessionID(boolean includeSessionID) {
		_includeSessionID = includeSessionID;
	}

	/**
	 * Sets the request handler key to redirect to. You typically want to also
	 * set requestHandlerPath if you set this.
	 * 
	 * @param requestHandlerKey
	 *            the redirected request handler key
	 * </span>
	 */
	public void setRequestHandlerKey(String requestHandlerKey) {
		_requestHandlerKey = requestHandlerKey;
	}

	/**
	 * Sets the request handler path to redirect to. This requires that you also
	 * set requestHandlerKey.
	 * 
	 * @param requestHandlerPath
	 *            the request handler path to redirect to
	 */
	public void setRequestHandlerPath(String requestHandlerPath) {
		_requestHandlerPath = requestHandlerPath;
	}

	/**
	 * Sets the direct action class to redirect to. You typically want to also
	 * set directActionName if you set this.
	 * 
	 * @param directActionClass
	 *            the direct action class to redirect to
	 */
	public void setDirectActionClass(String directActionClass) {
		_directActionClass = directActionClass;
	}

	/**
	 * The direct action name to redirect to.
	 * 
	 * @param directActionName
	 *            the direct action name
	 */
	public void setDirectActionName(String directActionName) {
		_directActionName = directActionName;
	}

	/**
	 * Sets the URL to redirect to.
	 * 
	 * @param url
	 *            the URL to redirect to
	 */
	public void setUrl(String url) {
		_url = url;
	}

	/**
	 * Sets the redirect component to be the original page that we were just on.
	 */
	public void setComponentToPage() {
		_component = _originalComponent;
	}

	/**
	 * Sets the component instance to redirect to. This component gets replaced
	 * as the page in the current context, and a URL is generated to the current
	 * context, which causes the request for that context ID to return the
	 * component you are redirecting to. When you set a redirect component, the
	 * component is also put into the normal page cache (rather than the ajax
	 * page cache), and the ajax cache is disabled for this request. As a
	 * result, redirecting to a component WILL burn a backtrack cache entry
	 * (just like a normal hyperlink).
	 * 
	 * @param component
	 *            the component instance to redirect to
	 */
	public void setComponent(WOComponent component) {
		_component = component;
	}

	/**
	 * Sets the query parameters for this redirect.
	 * 
	 * @param queryParameters
	 *            the query parameters for this redirect
	 */
	public void setQueryParameters(NSDictionary<String, ? extends Object> queryParameters) {
		_queryParameters = queryParameters;
	}

	/**
	 * Returns the query parameters dictionary as a string.
	 * 
	 * @return the query parameters as a string
	 */
	protected String queryParametersString() {
		String queryParametersString = null;
		if (_queryParameters != null && _queryParameters.count() > 0) {
			ERXMutableURL u = new ERXMutableURL();
			u.setQueryParameters(_queryParameters);
			queryParametersString = u.toExternalForm();
		}
		return queryParametersString;
	}
	
	protected NSDictionary<String, Object> directActionQueryParameters() {
		NSMutableDictionary<String, Object> params = null;
		if (_queryParameters != null) {
			params = (NSMutableDictionary<String, Object>) _queryParameters.mutableClone();
		} else {
			params = new NSMutableDictionary<>();
		}
		if (!_includeSessionID) {
			params.takeValueForKey(Boolean.FALSE.toString(), WOApplication.application().sessionIdKey());
		}
		return params;
	}

	@Override
	public void appendToResponse(WOResponse response, WOContext context) {
		String url;
		
		// Use secure binding if present, otherwise default to request setting
 		boolean secure = (_secure == null) ? ERXRequest.isRequestSecure(context.request()) : _secure.booleanValue();
 		
 		// Check whether we are currently generating complete URL's. We'll use this in finally() to reset the context to it's behavior before calling this.
 		boolean generatingCompleteURLs = context.doesGenerateCompleteURLs();
 
 		// Generate a full URL if changing between secure and insecure
		boolean generateCompleteURLs = secure != ERXRequest.isRequestSecure(context.request());
		if (generateCompleteURLs) {
		  context.generateCompleteURLs();
		}
		
		try {
			WOComponent component = _component;
			if (component != null) {
				
				// Build request handler path with session ID if needed
		        WOSession aSession = session();
				String aContextId = context.contextID();
				StringBuilder requestHandlerPath = new StringBuilder();
				if (WOApplication.application().pageCacheSize() == 0) {
					if (aSession.storesIDsInURLs()) {
						requestHandlerPath.append(component.name());
						requestHandlerPath.append('/');
						requestHandlerPath.append(aSession.sessionID());
						requestHandlerPath.append('/');
						requestHandlerPath.append(aContextId);
						requestHandlerPath.append(".0");
					}
					else {
						requestHandlerPath.append(component.name());
						requestHandlerPath.append('/');
						requestHandlerPath.append(aContextId);
						requestHandlerPath.append(".0");
					}
				}
				else if (aSession.storesIDsInURLs()) {
					requestHandlerPath.append(aSession.sessionID());
					requestHandlerPath.append('/');
					requestHandlerPath.append(aContextId);
					requestHandlerPath.append(".0");
				}
				else {
					requestHandlerPath.append(aContextId);
					requestHandlerPath.append(".0");
				}
				url = context._urlWithRequestHandlerKey(WOApplication.application().componentRequestHandlerKey(), requestHandlerPath.toString(), queryParametersString(), secure);
				context._setPageComponent(component);
			}
			else if (_url != null) {
				if (_secure != null) {
					throw new IllegalArgumentException("You specified a value for 'url' and for 'secure', which is not supported.");
				}
				url = _url;
				
				// the external url don't need it but if the url is a internal CMS Link then queryParamers is nice to have
				if (_queryParameters != null && _queryParameters.count() > 0)
					url += "?" + queryParametersString();
			}
			else if (_requestHandlerKey != null) {
				url = context._urlWithRequestHandlerKey(_requestHandlerKey, _requestHandlerPath, queryParametersString(), secure);
			}
			else if (_directActionName != null) {
				String requestHandlerPath;
				if (_directActionClass != null) {
					requestHandlerPath = _directActionClass + "/" + _directActionName;
				}
				else {
					requestHandlerPath = _directActionName;
				}
				url = context.directActionURLForActionNamed(requestHandlerPath, directActionQueryParameters(), secure, 0, false);
			}
			else {
				throw new IllegalStateException("You must provide a component, url, requestHandlerKey, or directActionName to this ERXRedirect.");
			}
	
			if (ERXAjaxApplication.isAjaxRequest(context.request())) {
				boolean hasUpdateContainer = context.request().stringFormValueForKey(ERXAjaxApplication.KEY_UPDATE_CONTAINER_ID) != null;
				if (hasUpdateContainer) {
					response.appendContentString("<script type=\"text/javascript\">");
				}
				else {
					response.setHeader("text/javascript", "Content-Type");
				}
				response.appendContentString("document.location.href='" + url + "';");
				if (hasUpdateContainer) {
					response.appendContentString("</script>");
				}
			}
			else {
				response.setHeader(url, "location");
				response.setStatus(302);
			}
	
			if (component != null) {
				ERXAjaxApplication.setForceStorePage(response);
			}
		}
		finally {
			// Switch the context back to the original url behaviour.
			if (generatingCompleteURLs) {
				context.generateCompleteURLs();
			} else {
				context.generateRelativeURLs();
			}
		}
	}
}
