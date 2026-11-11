# Checklist for a contribution to openhab-addons

The bundle already follows the layout of openhab-addons and builds there with all checks
(`.github/workflows/build.yml`: 0 SAT errors). Open points before a pull request:

- [ ] Annotate the `model` package and `SchulmanagerClient` with `@NonNullByDefault` and resolve the
      remaining null warnings (currently 9 SAT warnings plus "potential null pointer access" warnings).
- [ ] Use the shared Jetty `HttpClient` from `HttpClientFactory` instead of the JDK `java.net.http.HttpClient`
      (reviewers usually ask for this).
- [ ] Unit tests for the parsers (`DataParser`, `Letters`, `WidgetJson`) with anonymized sample responses.
- [ ] Decide on the text channels: German content is fine for a German-only service, but discuss it in the PR.
- [ ] Generate the i18n file with `mvn i18n:generate-default-translations` and check it against
      `OH-INF/i18n/schulmanager.properties`.
- [ ] Add the bundle to `bundles/pom.xml` and `CODEOWNERS` of openhab-addons, version of the target branch in `pom.xml`.
- [ ] Commits signed off with the real name (`git commit -s`, Developer Certificate of Origin).
- [ ] Widgets and page are not part of an add-on; offer them separately (Marketplace "UI Widgets" or the README).
