package org.frontcache.tags;

import javax.servlet.jsp.tagext.*;
import javax.servlet.jsp.*;
import java.io.*;

public class WebComponentTag extends SimpleTagSupport {

	private String maxage;

	public void setMaxage(String maxage) {
		this.maxage = maxage;
	}

	StringWriter sw = new StringWriter();

	public void doTag() throws JspException, IOException {

		JspWriter out = getJspContext().getOut();
		out.println("<fc:component cache-max-age=\"" + maxage + "\" />");
	}

}