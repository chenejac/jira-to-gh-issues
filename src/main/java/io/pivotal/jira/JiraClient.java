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
package io.pivotal.jira;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import lombok.Data;

/**
 * @author Rob Winch
 *
 */
@Data
@Component
public class JiraClient {
	@Autowired
	JiraConfig jiraConfig;

	RestOperations rest = new RestTemplate();

	public List<JiraIssue> findIssues(String jql) {

		List<JiraIssue> results = new ArrayList<>();
		Long startAt = 0L;

		while(startAt != null) {
			ResponseEntity<JiraSearchResult> result = rest.getForEntity(jiraConfig.getBaseUrl() + "/rest/api/2/search?maxResults=1000&startAt={0}&jql={jql}&fields=summary,comment,assignee,components,created,creator,description,fixVersions,issuetype,reporter,resolution,status,subtasks,issuelinks,resolution,updated,priority,labels,attachment", JiraSearchResult.class, startAt, jql);
			JiraSearchResult body = result.getBody();
			results.addAll(body.getIssues());
			startAt = body.getNextStartAt();
		}

		return results;
	}

	public JiraProject findProject(String id) {
		ResponseEntity<JiraProject> result = rest.getForEntity(jiraConfig.getBaseUrl() + "/rest/api/2/project/{id}", JiraProject.class, id);
		return result.getBody();
	}

	public List<JiraPriority> findPriorities() {
		List<JiraPriority> results = new ArrayList<JiraPriority>();
		/*System.out.println(jiraConfig.getBaseUrl() + "/rest/api/2/priority");
		ResponseEntity<JiraPriorities> result = rest.getForEntity(jiraConfig.getBaseUrl() + "/rest/api/2/priority", JiraPriorities.class);
		results = result.getBody();*/
		JiraPriority jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("cc0000");
		jiraPriority.setName("Blocker");
		jiraPriority.setId("10000");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("ff0000");
		jiraPriority.setName("Critical");
		jiraPriority.setId("10001");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("d04437");
		jiraPriority.setName("High (migrated)");
		jiraPriority.setId("10002");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("009900");
		jiraPriority.setName("Major");
		jiraPriority.setId("10003");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("d04437");
		jiraPriority.setName("Highest");
		jiraPriority.setId("1");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("f15C75");
		jiraPriority.setName("High");
		jiraPriority.setId("2");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("f79232");
		jiraPriority.setName("Medium");
		jiraPriority.setId("3");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("006600");
		jiraPriority.setName("Minor");
		jiraPriority.setId("10004");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("003300");
		jiraPriority.setName("Trivial");
		jiraPriority.setId("10005");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("707070");
		jiraPriority.setName("Low");
		jiraPriority.setId("4");
		results.add(jiraPriority);
		jiraPriority = new JiraPriority();
		jiraPriority.setStatusColor("999999");
		jiraPriority.setName("Lowest");
		jiraPriority.setId("5");
		results.add(jiraPriority);
		return results;
	}
}
