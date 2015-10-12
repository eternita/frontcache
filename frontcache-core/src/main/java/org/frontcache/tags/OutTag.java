package org.frontcache.tags;


/**
 * <p>Tag handler for &lt;out&gt; in JSTL's rtexprvalue library.</p>
 *
 * @author Shawn Bayern
 */

public class OutTag extends OutSupport {

    //*********************************************************************
    // Accessors
       
    // for tag attribute
    public void setValue(Object value) {
        this.value = value;
    }
      
    // for tag attribute
    public void setDefault(String def) {
        this.def = def;
    }
        
    // for tag attribute
    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }
}