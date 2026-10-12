		<%-- the license line - LicenseSummary, from the last poll --%>
		<c:if test="${not empty licenseSummary}">
			<div class="text-muted small" style="margin: 30px 0 10px 0; border-top: 1px solid #eee; padding-top: 8px;">
				<c:out value="${licenseSummary.footerText}"/>
				<c:if test="${licenseSummary.unlicensedFeatureCount gt 0}"> &middot; ${licenseSummary.unlicensedFeatureCount} Pro feature use<c:if test="${licenseSummary.unlicensedFeatureCount gt 1}">s</c:if> not covered by a license</c:if>
				&middot; <a href="license">License</a>
			</div>
		</c:if>
		</div>
		</div>
	</body>
</html>