package org.frontcache.tags;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.BodyTagSupport;

import org.frontcache.core.FCHeaders;


public class WebComponentSupport extends BodyTagSupport {


	// *********************************************************************
	// Internal state

//	protected Object value; // tag attribute
	protected String maxage;



	// *********************************************************************
	// Construction and initialization

	/**
	 * Constructs a new handler. As with TagSupport, subclasses should not
	 * provide other constructors and are expected to call the superclass
	 * constructor.
	 */
	public WebComponentSupport() {
		super();
		init();
	}

	// resets local state
	private void init() {
	}

	// Releases any resources we may have (or inherit)
	public void release() {
		super.release();
		init();
	}

	// *********************************************************************
	// Tag logic

	// evaluates 'value' 
	public int doStartTag() throws JspException {

		this.bodyContent = null; // clean-up body (just in case container is
									// pooling tag handlers)

		try {
			HttpServletResponse response = (HttpServletResponse) pageContext.getResponse();
			response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT, "true");
			
			// print maxage if available; otherwise, try 'default'
			if (maxage != null) {
				out(pageContext, maxage);
				return SKIP_BODY;
			}
			return SKIP_BODY;
		} catch (IOException ex) {
			throw new JspException(ex.toString(), ex);
		}
	}

	// prints the body if necessary; reports errors
	public int doEndTag() throws JspException {
		return EVAL_PAGE; // nothing more to do
	}

	// *********************************************************************
	// Public utility methods

	public static void out(PageContext pageContext, String maxage) throws IOException {
		HttpServletResponse response = (HttpServletResponse) pageContext.getResponse();
		response.addHeader(FCHeaders.X_FRONTCACHE_COMPONENT_MAX_AGE, maxage);
		
	}
}