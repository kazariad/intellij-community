package circlet.plugins.pipelines.services.execution

import circlet.pipelines.common.api.*
import circlet.pipelines.engine.api.*
import circlet.pipelines.engine.api.storage.*
import libraries.coroutines.extra.*
import libraries.klogging.*
import runtime.*

data class DummyContainer(val lifetimeSource: LifetimeSource)

class CircletIdeaJobExecutionProvider(
    private val lifetime: Lifetime,
    private val logCallback: (String) -> Unit,
    private val notifyProcessTerminated: (Int) -> Unit,
    private val db: CircletIdeaExecutionProviderStorage
) : JobExecutionProvider, JobExecutionScheduler {

    companion object : KLogging()

    private val runningJobs = mutableMapOf<Long, DummyContainer>()

    private lateinit var savedHandler: JobExecutionStatusUpdateHandler

    override fun scheduleExecution(jobExecs: Iterable<JobExecutionData<*>>) {
        jobExecs.forEach {
            logger.catch {
                launch { startExecution(it) }
            }
        }
    }

    override fun scheduleTermination(jobExecs: Iterable<JobExecutionData<*>>) {
        jobExecs.forEach {
            logger.catch {
                launch { startTermination(it) }
            }
        }
    }

    override suspend fun startExecution(jobExec: JobExecutionData<*>) = db("start-execution") {
        val jobEntity = db.findJobExecution(jobExec.id) ?: error("Job execution [$jobExec] is not found")
        if (jobEntity !is CircletIdeaAJobExecutionEntity) {
            error("unknown job $jobEntity")
        }

        val image = jobEntity.meta.image
        logCallback("prepare to run: image=$image, id=$jobExec")
        val jobLifetimeSource = lifetime.nested()

        val dummyContainer = DummyContainer(jobLifetimeSource)
        runningJobs[jobExec.id] = dummyContainer
        changeState(this, jobEntity, JobState.Running)

        var counter = 0

        val timer = UiDispatch.dispatchInterval(1000) {
            logCallback("run dummy container '$image'. counter = ${counter++}")
            if (counter == 3) {
                jobLifetimeSource.terminate()
            }
        }

        jobLifetimeSource.add {
            runningJobs.remove(jobExec.id)
            timer.cancel()
            logCallback("stop: image=$image, id=$jobExec")
            lifetime.launch(Ui) {
                db("start-execution") {
                    changeState(this, jobEntity, generateFinalState(image))
                }
            }
        }
    }

    override suspend fun startTermination(jobExec: JobExecutionData<*>) {
        TODO("startTermination not implemented")
    }

    override fun subscribeIdempotently(handler: JobExecutionStatusUpdateHandler) {
        this.savedHandler = handler
    }

    override fun onBeforeGraphStatusChanged(tx: AutomationStorageTransaction, events: Iterable<GraphStatusChangedEvent>) {
        if (events.single().newStatus.isFinished()) {
            notifyProcessTerminated(0)
        }
    }

    override fun onBeforeJobStatusChanged(tx: AutomationStorageTransaction, events: Iterable<JobStatusChangedEvent>) {
        //todo
    }

    private fun changeState(tx: AutomationStorageTransaction, job: AJobExecutionEntity<*>, newStatus: JobState) {
        savedHandler(tx, setOf(JobExecutionStatusUpdate(job, newStatus)))
    }

    private fun generateFinalState(imageName: String) : JobState {
        if (imageName.endsWith("_toFail")) {
            return JobState.Failed("Should fail because of the image name $imageName")
        }
        return JobState.Finished(0)
    }

    private fun launch(body: suspend () -> Unit) {
        launch(lifetime, Ui) {
            body()
        }
    }
}
