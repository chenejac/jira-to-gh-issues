/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pivotal.github;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.pivotal.jira.*;
import org.eclipse.egit.github.core.IRepositoryIdProvider;
import org.eclipse.egit.github.core.Label;
import org.eclipse.egit.github.core.Milestone;
import org.eclipse.egit.github.core.RepositoryId;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.RequestException;
import org.eclipse.egit.github.core.service.CommitService;
import org.eclipse.egit.github.core.service.IssueService;
import org.eclipse.egit.github.core.service.LabelService;
import org.eclipse.egit.github.core.service.MilestoneService;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.RequestEntity.BodyBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.pivotal.jira.JiraIssue.Fields;
import io.pivotal.util.MarkupEngine;
import io.pivotal.util.MarkupManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * @author Rob Winch
 *
 */
@Data
@Component
public class GithubClient {
	@Autowired
	GithubConfig config;
	@Autowired
	JiraConfig jiraConfig;

	Map<String, String> jiraUsernameToGithubUsername;

	RestTemplate rest = new GithubRestTemplate();

	static class GithubRestTemplate extends RestTemplate {
		public GithubRestTemplate() {
			super(new HttpComponentsClientHttpRequestFactory());
		}

		/* (non-Javadoc)
		 * @see org.springframework.web.client.RestTemplate#doExecute(java.net.URI, org.springframework.http.HttpMethod, org.springframework.web.client.RequestCallback, org.springframework.web.client.ResponseExtractor)
		 */
		@Override
		protected <T> T doExecute(URI url, HttpMethod method, RequestCallback requestCallback,
				ResponseExtractor<T> responseExtractor) throws RestClientException {
			return doExecute(url, method, requestCallback, responseExtractor, 45);
		}

		private <T> T doExecute(URI url, HttpMethod method, RequestCallback requestCallback,
				ResponseExtractor<T> responseExtractor, long abuseSleepTimeInSeconds) throws RestClientException {
			try {
				return super.doExecute(url, method, requestCallback, responseExtractor);
			} catch(HttpClientErrorException e) {
				HttpHeaders headers = e.getResponseHeaders();
				long sleep = 0;
				if(!"0".equals(headers.getFirst("X-RateLimit-Remaining"))) {
					if(e.getResponseBodyAsString().contains("https://developer.github.com/v3/#abuse-rate-limits")) {
						System.out.println("Recieved https://developer.github.com/v3/#abuse-rate-limits with no indication of how long to wait. Let's guess & wait "+abuseSleepTimeInSeconds+ " seconds.");
						sleep = TimeUnit.SECONDS.toMillis(abuseSleepTimeInSeconds);
					} else {
						System.out.println(e.getResponseBodyAsString());
						throw e;
					}
				} else {
					System.out.println("Received X-RateLimit-Reset. Waiting to do additional work");
					sleep = (1000 * Long.parseLong(headers.getFirst("X-RateLimit-Reset"))) - System.currentTimeMillis();
				}
				sleepFor(sleep);
			}
			return doExecute(url, method, requestCallback, responseExtractor, abuseSleepTimeInSeconds * 2);
		}

		private void sleepFor(long sleep) {
			if(sleep < 1) {
				return;
			}
			long endTime = System.currentTimeMillis() + sleep;
			System.out.println("Sleeping until "+ new DateTime(endTime));
			for(long now = System.currentTimeMillis(); now < endTime; now = System.currentTimeMillis()) {
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e1) {
				}
			}
			System.out.println("Continuing");
		}

	}

	@Autowired
	MarkupManager markup;

	@Autowired
	public void setUserMappingResource(@Value("classpath:jira-to-github-users.properties") Resource resource) throws IOException {
		Properties properties = new Properties();
		properties.load(resource.getInputStream());
		jiraUsernameToGithubUsername = new HashMap<>();

		for (final String name : properties.stringPropertyNames()) {
			jiraUsernameToGithubUsername.put(name, properties.getProperty(name));
		}
	}

	public String getRepositoryUrl() {
		return "https://api.github.com/repos/" + getRepositorySlug();
	}

	public void deleteRepository() throws IOException {
		if(!shouldDeleteCreateRepository()) {
			return;
		}
		String slug = getRepositorySlug();

		CommitService commitService = new CommitService(client());
		try {
			commitService.getCommits(createRepositoryId());
			throw new IllegalStateException("Attempting to delete a repository that has commits. Terminating!");
		} catch(RequestException e) {
			if(e.getStatus() != 404 & e.getStatus() != 409) {
				throw new IllegalStateException("Attempting to delete a repository, but it appears the repository may have commits. Terminating!", e);
			}
		}

		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("https://api.github.com/repos/" + slug)
				.queryParam("access_token", getAccessToken());
		rest.delete(uri.toUriString());
	}

	public void createRepository() throws IOException {
		if(!shouldDeleteCreateRepository()) {
			return;
		}
		String slug = getRepositorySlug();

		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString("https://api.github.com/user/repos")
				.queryParam("access_token", getAccessToken());
		Map<String, String> repository = new HashMap<>();
		repository.put("name", slug.split("/")[1]);
		rest.postForEntity(uri.toUriString(), repository, String.class);
	}

	public void createMilestones(List<JiraVersion> versions) throws IOException {
		MilestoneService milestones = new MilestoneService(client());
		for (JiraVersion version : versions) {
			Milestone milestone = new Milestone();
			milestone.setTitle(version.getName());
			milestone.setState(version.isReleased() ? "closed" : "open");
			if (version.getReleaseDate() != null) {
				milestone.setDueOn(version.getReleaseDate().toDate());
			}
			milestones.createMilestone(createRepositoryId(), milestone);
		}
	}

	public void createComponentLabels(List<JiraComponent> components) throws IOException {
		LabelService labels = new LabelService(client());
		for (JiraComponent component : components) {
			Label label = new Label();
			label.setColor("000000");
			label.setName(component.getName().replace(",", ";"));
			labels.createLabel(createRepositoryId(), label);
		}
	}

	public void createIssueTypeLabels(List<JiraIssueType> issueTypes) throws IOException {
		LabelService labels = new LabelService(client());
		Set<String> existingLabels = labels.getLabels(createRepositoryId()).stream().map(l -> l.getName().toLowerCase())
				.collect(Collectors.toSet());
		for (JiraIssueType issueType : issueTypes) {
			if (existingLabels.contains(issueType.getName().toLowerCase())) {
				continue;
			}
			Label label = new Label();
			label.setColor("eeeeee");
			label.setName(issueType.getName());
			labels.createLabel(createRepositoryId(), label);
		}
	}

	public void createPrioritiesLabels(List<JiraPriority> priorities) throws IOException{
		LabelService labels = new LabelService(client());
		Set<String> existingLabels = labels.getLabels(createRepositoryId()).stream().map(l -> l.getName().toLowerCase())
				.collect(Collectors.toSet());
		for (JiraPriority priority : priorities) {
			if (existingLabels.contains(priority.getName().toLowerCase())) {
				continue;
			}
			Label label = new Label();
			label.setColor(priority.getStatusColor());
			label.setName(priority.getName());
			labels.createLabel(createRepositoryId(), label);
		}
	}

	// https://gist.github.com/jonmagic/5282384165e0f86ef105#start-an-issue-import
	public void createIssues(List<JiraIssue> issues) throws IOException, InterruptedException {
		MilestoneService milestones = new MilestoneService(client());
		Map<String, Milestone> nameToMilestone = milestones.getMilestones(createRepositoryId(), "all").stream()
				.collect(Collectors.toMap(Milestone::getTitle, Function.identity()));

		Map<String,ImportedIssue> jiraIdToImportedIssue = new HashMap<>();
		int i = 0;
		for (JiraIssue issue : issues) {
			i++;
			ImportedIssue importedIssue = importIssue(nameToMilestone, issue);
			if(i % 100 == 0) {
				System.out.println("Migrated " + i + " issues");
			}
			jiraIdToImportedIssue.put(issue.getKey(),importedIssue);
		}
		System.out.println("Migrated " + i + " issues total");

		System.out.println("Creating backported issues");
		int b = 0;
		for(ImportedIssue importedIssue : jiraIdToImportedIssue.values()) {
			if(importedIssue.getBackportVersions().isEmpty()) {
				continue;
			}
			createBackports(nameToMilestone, importedIssue);
			b += importedIssue.getBackportVersions().size();
			if(b % 100 == 0) {
				System.out.println("Backported " + b + " issues");
			}
		}
		System.out.println("Backported "+b+" issues total");

		IssueService issueService = new IssueService(client());
		for(ImportedIssue importedIssue : jiraIdToImportedIssue.values()) {
			int issueNumber = getImportedIssueNumber(importedIssue);
			List<IssueLink> outwardIssueLinks = importedIssue.getJiraIssue().getFields().getIssuelinks().stream().filter( l -> l.getOutwardIssue() != null).collect(Collectors.toList());
			if(outwardIssueLinks.isEmpty()) {
				continue;
			}
			String comment = "\n";
			for(IssueLink outward : outwardIssueLinks) {
				String linkedJiraKey = outward.getOutwardIssue().getKey();
				// might be null if linked to a JIRA that was not queried (i.e. we migrate Spring Security and it relates to Spring Framework)
				ImportedIssue linkedIssue = jiraIdToImportedIssue.get(linkedJiraKey);
				String linkedIssueReference = linkedIssue == null ?
						JiraIssue.getBrowserUrl(jiraConfig.getBaseUrl(), linkedJiraKey) : getImportedIssueReference(linkedIssue);
				comment += "\nThis issue " + outward.getType().getOutward() + " " + linkedIssueReference;
			}
			issueService.createComment(createRepositoryId(), issueNumber, comment);
		}

	}

	private void createBackports(Map<String, Milestone> nameToMilestone, ImportedIssue importedIssue) throws InterruptedException, IOException {
		String url = getImportedIssueReference(importedIssue);
		JiraIssue jiraIssue = importedIssue.getJiraIssue();
		for(JiraFixVersion version : importedIssue.getBackportVersions()) {
			GithubIssue issue = createGithubIssue(nameToMilestone, jiraIssue, version);
			issue.setBody("Backported " + url);
			issue.getLabels().add("Backport");

			ImportGithubIssue toImport = new ImportGithubIssue();
			toImport.setIssue(issue);

			importIssue(toImport);
		}
	}

	private String getImportedIssueReference(ImportedIssue importedIssue) throws InterruptedException {
		return "#" + getImportedIssueNumber(importedIssue);
	}

	private int getImportedIssueNumber(ImportedIssue importedIssue) throws InterruptedException {
		if(importedIssue.getIssueNumber() != null) {
			return importedIssue.getIssueNumber();
		}
		String importIssuesUrl = importedIssue.getImportResponse().getUrl();

		URI uri = UriComponentsBuilder
				.fromUriString(importIssuesUrl)
				.build()
				.toUri();
		RequestEntity<Void> request = RequestEntity.get(uri)
				.accept(new MediaType("application", "vnd.github.golden-comet-preview+json"))
				.header("Authorization", "token " + getAccessToken())
				.build();

		long secondsToWait = 1;
		while(true) {
			ResponseEntity<ImportStatusResponse> result = rest.exchange(request, ImportStatusResponse.class);
			String url = result.getBody().getIssueUrl();
			if(url == null) {
				System.out.println("Issue "+ importedIssue.getJiraIssue().getKey()+" is still pending import, waiting "+secondsToWait +" seconds to look up GitHub issue number again");
				Thread.sleep(TimeUnit.SECONDS.toMillis(secondsToWait));
				secondsToWait = (1 + secondsToWait) * 2;
				continue;
			}
			UriComponents parts = UriComponentsBuilder.fromUriString(url).build();
			List<String> segments = parts.getPathSegments();
			int issueNumber = Integer.parseInt(segments.get(segments.size() - 1));
			importedIssue.setIssueNumber(issueNumber);
			return issueNumber;
		}
	}

	private ImportedIssue importIssue(Map<String, Milestone> nameToMilestone, JiraIssue issue) throws IOException {
		List<JiraFixVersion> fixVersions = JiraFixVersion.sort(issue.getFields().getFixVersions());
		JiraFixVersion fixVersion = fixVersions.isEmpty() ? null : fixVersions.get(0);

		ImportGithubIssue importIssue = createImportIssue(nameToMilestone, issue, fixVersion);

		ResponseEntity<ImportGithubIssueResponse> result = importIssue(importIssue);
		ImportGithubIssueResponse importResponse = result.getBody();
		List<JiraFixVersion> versionsToBackport = fixVersions.size() <= 1 ? Collections.emptyList() : fixVersions.subList(1, fixVersions.size());

		return new ImportedIssue(issue, importResponse, versionsToBackport);

	}
//	private ImportGithubIssue wrong = null;

	private ResponseEntity<ImportGithubIssueResponse> importIssue(ImportGithubIssue importIssue) {
		URI uri = UriComponentsBuilder
				.fromUriString(getRepositoryUrl())
				.pathSegment("import", "issues")
				.build()
				.toUri();
		BodyBuilder request = RequestEntity.post(uri)
				.accept(new MediaType("application", "vnd.github.golden-comet-preview+json"))
				.header("Authorization", "token " + getAccessToken());

/*if(wrong == null){
	wrong = importIssue;
} else {
System.out.println(wrong.getIssue().getAssignee());
System.out.println(importIssue.getIssue().getAssignee());
	importIssue.getIssue().setAssignee("chenejac");
}*/
		if(importIssue.getIssue().getAssignee() != null){
			importIssue.getIssue().setAssignee("chenejac");
		}

		ResponseEntity<ImportGithubIssueResponse> result = rest.exchange(request.body(importIssue), ImportGithubIssueResponse.class);
		result.getBody().setImportIssue(importIssue);
		return result;
	}

	private ImportGithubIssue createImportIssue(Map<String, Milestone> nameToMilestone, JiraIssue issue, JiraFixVersion version) throws IOException {
		ImportGithubIssue importIssue = new ImportGithubIssue();

		GithubIssue ghIssue = createGithubIssue(nameToMilestone, issue, version);

		List<GithubComment> comments = createComments(issue);

		importIssue.setIssue(ghIssue);
		importIssue.setComments(comments);
		return importIssue;
	}

	private GithubIssue createGithubIssue(Map<String, Milestone> nameToMilestone, JiraIssue issue, JiraFixVersion fixVersion) throws IOException {
		Fields fields = issue.getFields();
		boolean closed = fields.getStatus().getName().equals("Closed");
		DateTime updated = fields.getUpdated();
		GithubIssue ghIssue = new GithubIssue();
		ghIssue.setTitle(issue.getKey() + ": " + fields.getSummary());

		String migratedLink = issue.getBrowserUrl();
		MarkupEngine engine = markup.engine(issue.getFields().getCreated());
		String migrated = "Migrated from " + engine.link(issue.getKey(), migratedLink);
		String body;
		if(fields.getDescription() != null) {
			String ghUsername = jiraUsernameToGithubUsername.get(fields.getReporter().getAccountId());
			if (ghUsername != null) {
				body = engine.link(fields.getReporter().getDisplayName(), "https://github.com/"+ghUsername) + " (" + migrated + ")" + " said:\n\n";
			}
			else {
				body = engine.link(fields.getReporter().getDisplayName(), fields.getReporter().getBrowserUrl(jiraConfig.getBaseUrl() + jiraConfig.getUserProfilePage())) + " (" + migrated + ")" + " said:\n\n";
			}
			String description = fields.getDescription();
			for (JiraAttachment att:fields.getAttachment()
				 ) {
				description = description.replace("!"+att.getFilename()+"!", "![](" + att.getContent() + "?raw=true)");
			}
			body += engine.convert(description);
		} else {
			body = migrated;
		}
		ghIssue.setBody(body);
		ghIssue.setClosed(closed);
		if (closed) {
			ghIssue.setClosedAt(updated);
		}
		JiraUser assignee = issue.getFields().getAssignee();
		if (assignee != null) {
			String ghUsername = jiraUsernameToGithubUsername.get(assignee.getAccountId());
			if (ghUsername != null) {
				ghIssue.setAssignee(ghUsername);
			}
		}
		ghIssue.setCreatedAt(fields.getCreated());
		ghIssue.setUpdatedAt(updated);

		if (fixVersion != null) {
			Milestone m = nameToMilestone.get(fixVersion.getName());
			if(m == null) {
				throw new IllegalStateException("Could not map fix version "+fixVersion.getName() + " to a github milestone. Available options are "+nameToMilestone.keySet());
			}
			ghIssue.setMilestone(m.getNumber());
		}

		LabelService labels = new LabelService(client());
		Set<String> existingLabels = labels.getLabels(createRepositoryId()).stream().map(l -> l.getName())
				.collect(Collectors.toSet());

		List<String> componentNames = fields.getComponents().stream()
			.map(JiraComponent::getName)
			.collect(Collectors.toList());
		for (String componentName:componentNames
			 ) {
			addLabelToGithubIssue(ghIssue, componentName);
		}

		JiraStatus status = fields.getStatus();
		if(status != null) {
			addLabelToGithubIssue(ghIssue, status.getName());
		}

		JiraIssueType issueType = fields.getIssuetype();
		if(issueType != null) {
			addLabelToGithubIssue(ghIssue, issueType.getName());
		}

		JiraResolution jiraResolution = fields.getResolution();
		if(jiraResolution != null) {
			addLabelToGithubIssue(ghIssue, jiraResolution.getName());
		}

		JiraPriority priority = fields.getPriority();
		if(priority != null) {
			addLabelToGithubIssue(ghIssue, priority.getName());
		}

		for (String lab:fields.getLabels()
			 ) {
			try{
				addLabelToGithubIssue(ghIssue, lab);
			} catch (Exception e){

			}
		}

		addLabelToGithubIssue(ghIssue, "Jira");

		return ghIssue;
	}

	private void addLabelToGithubIssue(GithubIssue ghIssue, String labelString) throws IOException {
		LabelService labels = new LabelService(client());
		try{
			Set<String> existingLabels = labels.getLabels(createRepositoryId()).stream().map(l -> l.getName())
				.collect(Collectors.toSet());


			if(labelString != null) {
				labelString = labelString.replace(",", ";");
				if (existingLabels.contains(labelString)) {
					ghIssue.getLabels().add(labelString);
				} else if (existingLabels.contains(labelString.toLowerCase())) {
					ghIssue.getLabels().add(labelString.toLowerCase());
				} else {
					Label label = new Label();
					label.setColor("ffffff");
					label.setName(labelString);
					try {
						labels.createLabel(createRepositoryId(), label);
					} catch (Exception e) {
						e.printStackTrace();
					}
					ghIssue.getLabels().add(labelString);
				}
			}
		} catch (RequestException ex){
			if(ex.getMessage().startsWith("API rate limit exceeded")){
				long sleep = 3600000;
				long endTime = System.currentTimeMillis() + sleep;
				System.out.println("Sleeping until "+ new DateTime(endTime));
				for(long now = System.currentTimeMillis(); now < endTime; now = System.currentTimeMillis()) {
					try {
						Thread.sleep(sleep);
					} catch (InterruptedException e1) {
					}
				}
				System.out.println("Continuing");
				addLabelToGithubIssue(ghIssue, labelString);
			}
		}
	}

	private List<GithubComment> createComments(JiraIssue issue) {
		Fields fields = issue.getFields();
		List<GithubComment> comments = new ArrayList<>();
		for (JiraComment jiraComment : fields.getComment().getComments()) {
			GithubComment comment = new GithubComment();
			MarkupEngine engine = markup.engine(jiraComment.getCreated());
			String ghUsername = jiraUsernameToGithubUsername.get(jiraComment.getAuthor().getAccountId());
			String userUrl = null;
			if (ghUsername != null) {
				userUrl = "https://github.com/"+ghUsername;
			}
			else {
				userUrl = jiraComment.getAuthor().getBrowserUrl(jiraConfig.getBaseUrl() + jiraConfig.getUserProfilePage());
			}
			String body = engine.link(jiraComment.getAuthor().getDisplayName(), userUrl) + " said:\n\n";
			body += engine.convert(jiraComment.getBody());
			comment.setBody(body);
			comment.setCreatedAt(jiraComment.getCreated());
			comments.add(comment);
		}
		return comments;
	}

	private IRepositoryIdProvider createRepositoryId() {
		return RepositoryId.createFromId(getRepositorySlug());
	}

	private String getRepositorySlug() {
		return config.getRepositorySlug();
	}

	private boolean shouldDeleteCreateRepository() {
		return config.isDeleteCreateRepositorySlug();
	}

	private GitHubClient client() {

		GitHubClient githubClient = new GitHubClient() {

			@Override
			protected HttpURLConnection configureRequest(HttpURLConnection request) {
				HttpURLConnection result = super.configureRequest(request);
				result.setRequestProperty(HEADER_ACCEPT, MediaType.APPLICATION_JSON_VALUE);
				return result;
			}

			@Override
			public <V> V post(String uri, Object params, Type type) throws IOException {
				HttpURLConnection request = this.createPost(uri);
				return this.sendJson2(request, params, type);
			}

			private <V> V sendJson2(HttpURLConnection request, Object params, Type type) throws IOException {
				if(params instanceof Milestone)
					this.sendParams(request, new SimpleMilestone((Milestone) params));
				else
					this.sendParams(request, params);
				int code = request.getResponseCode();
				System.out.println(this.toJson(params));

				this.updateRateLimits(request);
				if (this.isOk(code)) {
					return type != null ? this.parseJson(this.getStream(request), type) : null;
				} else if (this.isEmpty(code)) {
					return null;
				} else {
					throw this.createException(this.getStream(request), code, request.getResponseMessage());
				}
			}

		};
		githubClient.setOAuth2Token(getAccessToken());
		return githubClient;
	}

	private String getAccessToken() {
		return config.getAccessToken();
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	static class ImportStatusResponse {
		@JsonProperty("issue_url")
		String issueUrl;
	}

	@Data
	@RequiredArgsConstructor
	static class ImportedIssue {
		/**
		 * The GitHub issue number (may be null). This is filled out after the
		 * importResponse is post processed.
		 */
		Integer issueNumber;
		final JiraIssue jiraIssue;
		final ImportGithubIssueResponse importResponse;
		final List<JiraFixVersion> backportVersions;

	}


	@JsonIgnoreProperties(ignoreUnknown = true)
	@Data
	static class ImportGithubIssueResponse {
		ImportGithubIssue importIssue;
		String url;
		String status;
		List<Error> errors;

		public boolean isFailed() {
			return "failed".equals(status);
		}

		@Data
		static class Error {
			String code;
			String field;
			String location;
			String resource;
			String value;
		}
	}
}
