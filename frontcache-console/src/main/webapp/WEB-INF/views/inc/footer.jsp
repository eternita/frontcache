		<%-- footer lines from console plugins (org.frontcache.console.plugin.ConsoleNoticeProvider) --%>
		<c:forEach var="line" items="${consoleNotices.footerLines}">
			<div class="text-muted small" style="margin: 30px 0 10px 0; border-top: 1px solid #eee; padding-top: 8px;">
				<c:out value="${line.text}"/><c:if test="${not empty line.href}"> &middot; <a href="<c:out value='${line.href}'/>"><c:out value="${line.linkText}"/></a></c:if>
			</div>
		</c:forEach>
		</div>
		</div>
	</body>
</html>