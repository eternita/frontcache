<%@ taglib prefix="c" uri="jakarta.tags.core"%>
<%
	// The console war has no character-encoding filter for the RESPONSE side, and header.jsp declares
	// pageEncoding="ISO-8859-1" while its meta tag says UTF-8. A config file is exactly where non-ASCII
	// turns up - a User-Agent keyword, a URL, a comment in someone's language - and this page writes
	// what it renders back to the node, so mojibake here would be written to disk. Same line
	// cache_invalidation.jsp already carries, for the same reason.
	response.setCharacterEncoding("UTF-8");
%>
<%@ include file="/WEB-INF/views/inc/header.jsp"%>

<%@ include file="/WEB-INF/views/inc/config_menu.jsp"%>
<%@ include file="/WEB-INF/views/inc/config_file_editor.jsp"%>

<%@ include file="/WEB-INF/views/inc/footer.jsp"%>
