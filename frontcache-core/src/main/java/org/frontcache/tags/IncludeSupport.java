package org.frontcache.tags;

import java.io.IOException;
import java.io.Reader;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.BodyTagSupport;


public class IncludeSupport extends BodyTagSupport {


	// *********************************************************************
	// Internal state

	protected Object ulr; // tag attribute
	protected Object includeType; // tag attribute

	// *********************************************************************
	// Construction and initialization

	/**
	 * Constructs a new handler. As with TagSupport, subclasses should not
	 * provide other constructors and are expected to call the superclass
	 * constructor.
	 */
	public IncludeSupport() {
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
			// print value if available; otherwise, try 'default'
			if (ulr != null) {
				out(pageContext, ulr, includeType);
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

	public static void out(PageContext pageContext, Object url, Object includeType) throws IOException {
		JspWriter w = pageContext.getOut();
		
		w.write("<fc:include url=\"");
		// write chars as is
		if (url instanceof Reader) {
			Reader reader = (Reader) url;
			char[] buf = new char[4096];
			int count;
			while ((count = reader.read(buf, 0, 4096)) != -1) {
				w.write(buf, 0, count);
			}
		} else {
			w.write(url.toString());
		}
		
		w.write("\"");
		
		if (null != includeType)
		{
			w.write(" type=\"");
			if (includeType instanceof Reader) {
				Reader reader = (Reader) includeType;
				char[] buf = new char[4096];
				int count;
				while ((count = reader.read(buf, 0, 4096)) != -1) {
					w.write(buf, 0, count);
				}
			} else {
				w.write(includeType.toString());
			}
			
			w.write("\"");
		}
			
		w.write(" />");
	}
}