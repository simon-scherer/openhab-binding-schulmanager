# Checklist for a contribution to openhab-addons

The bundle already follows the layout of openhab-addons and builds there with all checks
(`.github/workflows/build.yml`: 0 SAT errors). Open points before a pull request:

- [x] All classes annotated with `@NonNullByDefault`, nullable fields marked `@Nullable`.
- [x] The shared Jetty `HttpClient` from `HttpClientFactory` is used (with a larger response buffer and handling
      of HTTP 401 without `WWW-Authenticate`, which Jetty otherwise reports as protocol violation).
- [ ] Unit tests for the parsers (`DataParser`, `Letters`, `WidgetJson`) with anonymized sample responses.
- [ ] Decide on the text channels: German content is fine for a German-only service, but discuss it in the PR.
- [ ] Generate the i18n file with `mvn i18n:generate-default-translations` and check it against
      `OH-INF/i18n/schulmanager.properties`.
- [ ] Add the bundle to `bundles/pom.xml` and `CODEOWNERS` of openhab-addons, version of the target branch in `pom.xml`.
- [ ] Commits signed off with the real name (`git commit -s`, Developer Certificate of Origin).
- [ ] Widgets and page are not part of an add-on; offer them separately (Marketplace "UI Widgets" or the README).
