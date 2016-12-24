package org.frontcache.tags;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.BodyTagSupport;

import org.frontcache.core.FCHeaders;


@SuppressWarnings("serial")
public class WebComponentSupport extends BodyTagSupport {


	protected String maxage;
	protected String tags;
	protected String refresh;
	protected String level;

	/**
	 * Constructs a new handler. As with TagSupport, subclasses should not
	 * provide other constructors and are expected to call the superclass
	 * constructor.
	 */
	public WebComponentSupport() {
		super();
		init();
	}

	private void init() {
		this.maxage = null;
		this.tags = null;
		this.refresh = null;
	}

	// Releases any resources we may have (or inherit)
	public void release() {
		super.release();
		init();
	}


	public int doStartTag() throws JspException {

		this.bodyContent = null; // clean-up body (just in case container is pooling tag handlers)

		try {
			HttpServletResponse response = (HttpServletResponse) pageContext.getResponse();

			response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT, "true");
			
			if (null != maxage)
				response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, maxage);
			
			if (null != tags)
				response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT_TAGS, tags);
			
			if (null != refresh)
				response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT_REFRESH_TYPE, refresh);
			
			if (null != level)
				response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT_CACHE_LEVEL, level);
			
			return SKIP_BODY;
		} catch (Exception ex) {
			throw new JspException(ex.toString(), ex);
		}
	}

	// prints the body if necessary; reports errors
	public int doEndTag() throws JspException {
		return EVAL_PAGE; // nothing more to do
	}

}