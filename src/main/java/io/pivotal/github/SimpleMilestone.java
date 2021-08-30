package io.pivotal.github;

import org.eclipse.egit.github.core.Milestone;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.util.DateUtils;

import java.util.Date;

public class SimpleMilestone {

    private static final long serialVersionUID = -8087355312095381516L;
    private Date dueOn;
    private String description;
    private String state;
    private String title;

    public SimpleMilestone(Milestone milestone) {
        this.dueOn = (milestone.getDueOn()==null?new Date():milestone.getDueOn());
        this.description = (milestone.getDescription()==null)?"":milestone.getDescription();
        this.state = milestone.getState();
        this.title = milestone.getTitle();
    }
}
