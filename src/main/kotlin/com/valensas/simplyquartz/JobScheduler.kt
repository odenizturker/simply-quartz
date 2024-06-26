package com.valensas.simplyquartz

import org.quartz.CronScheduleBuilder
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.quartz.impl.matchers.GroupMatcher
import org.reflections.Reflections
import org.reflections.util.ConfigurationBuilder
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

// Need to get the main application class to determine the root package for job scanning at runtime
@Configuration
class MainClassHolder : ApplicationListener<ApplicationStartedEvent> {
    var mainApplicationClass: Class<*>? = null

    override fun onApplicationEvent(event: ApplicationStartedEvent) {
        mainApplicationClass = event.springApplication.mainApplicationClass
    }
}

@Configuration
@ConditionalOnProperty("simplyquartz.enabled", havingValue = "true")
@EnableConfigurationProperties(SimplyQuartzProperties::class)
class JobScheduler(
    private val scheduler: Scheduler,
    private val applicationContext: ApplicationContext,
    private val simplyQuartzProperties: SimplyQuartzProperties,
    private val mainClassHolder: MainClassHolder
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        logger.info("Scheduling jobs")
        scanAndScheduleJobs()
    }

    private fun scanAndScheduleJobs() {
        val existingJobKeysSet = scheduler.getJobKeys(GroupMatcher.anyGroup()).toSet()
        val newJobKeysSet = mutableSetOf<JobKey>()

        val jobsSearchPackages = determineJobsSearchPathRootPackage()
        val reflections = Reflections(
            ConfigurationBuilder()
                .forPackages(*jobsSearchPackages.toTypedArray())
        )

        reflections.getSubTypesOf(Job::class.java).forEach { jobClass ->
            jobClass.getAnnotation(QuartzSchedule::class.java)?.let { scheduleAnnotation ->
                val jobName =
                    resolveEnvironmentPlaceholders(scheduleAnnotation.jobName)
                        .ifBlank { jobClass.simpleName }
                val jobGroup =
                    resolveEnvironmentPlaceholders(scheduleAnnotation.jobGroup)
                        .ifBlank { jobClass.`package`.name }
                val cronExpression = resolveEnvironmentPlaceholders(scheduleAnnotation.cron)
                scheduleJob(jobClass, jobName, jobGroup, cronExpression)
                newJobKeysSet.add(JobKey.jobKey(jobName, jobGroup))
            }
        }

        val jobKeysToBeRemoved = existingJobKeysSet.minus(newJobKeysSet)

        jobKeysToBeRemoved.forEach { jobKey ->
            logger.info("Removing undeclared job ${jobKey.name}")
            scheduler.deleteJob(jobKey)
        }

        logger.info("Starting scheduler")

        scheduler.start()
    }

    private fun resolveEnvironmentPlaceholders(value: String): String {
        return applicationContext.environment.resolvePlaceholders(value)
    }

    // Get the root package for job scanning, defaulting to the package of the main application class
    private fun determineJobsSearchPathRootPackage(): List<String> {
        return simplyQuartzProperties.packagesToScan
            ?: mainClassHolder.mainApplicationClass?.`package`?.name?.let { listOf(it) }
            ?: throw IllegalStateException("Unable to determine base package for job scanning")
    }

    private fun scheduleJob(clazz: Class<out Job>, jobName: String, jobGroup: String, cronExpression: String) {
        val jobKey = JobKey.jobKey(jobName, jobGroup)
        val newTrigger = TriggerBuilder.newTrigger()
            .withIdentity(jobName, jobGroup)
            .forJob(jobName, jobGroup)
            .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
            .build()
        val jobDetail = JobBuilder.newJob(clazz)
            .requestRecovery(false)
            .withIdentity(jobName, jobGroup)
            .storeDurably()
            .build()

        if (scheduler.checkExists(jobKey)) {
            scheduler.rescheduleJob(newTrigger.key, newTrigger)
        } else {
            scheduler.scheduleJob(jobDetail, newTrigger)
        }
    }
}
