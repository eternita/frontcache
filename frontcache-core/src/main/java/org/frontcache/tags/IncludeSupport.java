/**
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.frontcache.tags;

import java.io.IOException;
import java.io.Reader;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.BodyTagSupport;


@SuppressWarnings("serial")
public class IncludeSupport extends BodyTagSupport {


	// *********************************************************************
	// Internal state

	protected Object ulr; // tag attribute
	protected Object includeCallType; // tag attribute
	protected Object includeClientType; // tag attribute
	protected Object includeCombine; // tag attribute - see docs/combine-reduce-proposal.md

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
				out(pageContext, ulr, includeCallType, includeClientType, includeCombine);
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

	/**
	 * Kept so anything calling the pre-combine signature still compiles and behaves identically -
	 * no combine attribute is the same as no combining.
	 */
	public static void out(PageContext pageContext, Object url, Object includeType, Object clientType) throws IOException {
		out(pageContext, url, includeType, clientType, null);
	}

	public static void out(PageContext pageContext, Object url, Object includeType, Object clientType, Object combine) throws IOException {
		JspWriter w = pageContext.getOut();

		w.write("<fc:include url=\"");
		writeValue(w, url); // write chars as is
		w.write("\"");

		if (null != includeType)
		{
			w.write(" call=\"");
			writeValue(w, includeType);
			w.write("\"");
		}

		if (null != clientType)
		{
			w.write(" client=\"");
			// writeValue(w, clientType), not includeType: this block tested and streamed includeType
			// while writing the client attribute, so a Reader-valued client="..." emitted the CALL
			// type into the client attribute. Only reachable through the rtexprvalue path with a
			// Reader, which is why it survived.
			writeValue(w, clientType);
			w.write("\"");
		}

		if (null != combine)
		{
			w.write(" combine=\"");
			writeValue(w, combine);
			w.write("\"");
		}

		w.write(" />");
	}

	/**
	 * Writes an attribute value, streaming it when the caller handed a Reader.
	 *
	 * One copy, where there were three near-identical ones - and one of the three read
	 * {@code includeType} while writing the {@code client} attribute.
	 */
	private static void writeValue(JspWriter w, Object value) throws IOException {

		if (value instanceof Reader) {
			Reader reader = (Reader) value;
			char[] buf = new char[4096];
			int count;
			while ((count = reader.read(buf, 0, 4096)) != -1) {
				w.write(buf, 0, count);
			}
		} else {
			w.write(value.toString());
		}
	}
}