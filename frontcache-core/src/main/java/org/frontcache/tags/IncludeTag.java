package org.frontcache.tags;

public class IncludeTag extends IncludeSupport {

//   private String url;

   public void setUrl(Object url) {
      super.value = url;
   }

//   StringWriter sw = new StringWriter();

//   public void doTag()
//      throws JspException, IOException
//    {
//       if (url != null) {
//          /* Use url from attribute */
//          JspWriter out = getJspContext().getOut();
//          out.println( "<fc:include url=\"" + url + "\" />" );
//       }
////       else {
////          /* use url from the body */
////          getJspBody().invoke(sw);
////          getJspContext().getOut().println(sw.toString());
////       }
//   }

}