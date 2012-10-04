package com.jobsnearyou.scheduler;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.social.linkedin.api.Job;
import org.springframework.social.linkedin.api.JobOperations;
import org.springframework.social.linkedin.api.JobSearchParameters;
import org.springframework.social.linkedin.api.Jobs;
import org.springframework.social.linkedin.api.impl.LinkedInTemplate;
import org.springframework.stereotype.Component;

import com.jobsnearyou.domain.LinkedinJob;

@Component
public class LinkedInJobFinderScheduler {

	private static final int SEARCH_COUNT = 5;

	private final String[] skills = { "java", "ruby", "python", "node.js",
			"mongodb" };

	@Inject
	MongoTemplate mongoTemplate;

	LinkedInTemplate linkedIn = new LinkedInTemplate("iejn5z7fb7co",
			"yJJM2VoFt2E5nq4c", "a638a578-37e0-4cc0-8694-e56b13c44405",
			"b272ef6d-b85f-42d3-b0f4-23ba788e1369");

	@Inject
	GooglePlacesClient googlePlacesClient;
	int jobCount = 1;

	@Scheduled(fixedDelay = 180L * 60 * 1000)
	public void findJobs() {
		System.out.println(jobCount + " ..... Running Job ...." + new Date());
		JobOperations jobOperations = linkedIn.jobOperations();
		for (String skill : skills) {
			findAndPersistJobsPerSkill(linkedIn, jobOperations, skill);
		}
		System.out.println(jobCount + " ..... Finished Job ...." + new Date());
		jobCount++;
	}

	private void findAndPersistJobsPerSkill(LinkedInTemplate linkedIn,
			JobOperations jobOperations, String skill) {
		JobSearchParameters parameters = new JobSearchParameters();
		parameters.setKeywords(skill);
		parameters.setCount(SEARCH_COUNT);
		Jobs jobs = jobOperations.searchJobs(parameters);
		List<Job> allJobs = jobs.getJobs();
		if (allJobs == null) {
			System.out.println("No job found for " + skill);
			return;
		}
		List<LinkedinJob> linkedinJobs = new ArrayList<LinkedinJob>();
		for (Job job : allJobs) {
			if (!jobExistsInDatabase(job)) {
				LinkedinJob linkedinJob = convertToLinkedInJob(linkedIn, job,
						skill);

				LinkedinJob jobWithLocation = findLinkedJobWithSimilarLocation(linkedinJob);

				if (jobWithLocation != null) {
					linkedinJob.setLocation(jobWithLocation.getLocation());
					linkedinJob.setFormattedAddress(jobWithLocation
							.getFormattedAddress());
				}
				else {
					String locationQuery = toLocationQuery(linkedinJob);
					Place place = googlePlacesClient
							.performTextSearch(locationQuery);
					if (place == null) {
						continue;
					}
					linkedinJob.setFormattedAddress(place.formatted_address);
					linkedinJob.setLocation(new double[] {
							place.geometry.location.lat,
							place.geometry.location.lng });
					System.out.println("Place " + place);
				}

				System.out.println(linkedinJob);
				linkedinJobs.add(linkedinJob);
			}

		}
		mongoTemplate.insertAll(linkedinJobs);
	}

	private LinkedinJob findLinkedJobWithSimilarLocation(LinkedinJob linkedinJob) {
		Query jobLocationQuery = new Query(Criteria
				.where("locationDescription")
				.is(linkedinJob.getLocationDescription())
				.and("company.companyName")
				.is(linkedinJob.getCompany().getCompanyName()));

		jobLocationQuery.fields().include("formattedAddress")
				.include("location");

		LinkedinJob jobWithLocation = mongoTemplate.findOne(jobLocationQuery,
				LinkedinJob.class);
		return jobWithLocation;
	}

	private String toLocationQuery(LinkedinJob linkedinJob) {
		String companyName = linkedinJob.getCompany().getCompanyName();
		String locationDescription = linkedinJob.getLocationDescription();
		locationDescription = StringUtils
				.replace(locationDescription, "-", ",");
		return new StringBuilder(companyName).append(",")
				.append(locationDescription).toString();
	}

	private LinkedinJob convertToLinkedInJob(LinkedInTemplate linkedIn,
			Job job, String skill) {
		LinkedinJob linkedinJob = new LinkedinJob(job, skill);
		return linkedinJob;
	}

	private boolean jobExistsInDatabase(Job job) {
		Query query = Query.query(Criteria.where("linkedinJobId").is(
				job.getId()));
		return mongoTemplate.findOne(query, Job.class) == null ? false : true;
	}

}
