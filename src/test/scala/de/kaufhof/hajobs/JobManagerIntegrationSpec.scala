package de.kaufhof.hajobs

import java.util.concurrent.CountDownLatch

import akka.actor.ActorSystem
import de.kaufhof.hajobs.testutils.CassandraSpec
import org.quartz.Scheduler
import org.scalatest.mock.MockitoSugar
import play.api.test._

import scala.concurrent.Future
import scala.language.postfixOps

class JobManagerIntegrationSpec extends CassandraSpec with DefaultAwaitTimeout with FutureAwaits with MockitoSugar {

  import JobManagerIntegrationSpec._

  private lazy val jobStatusRepository = new JobStatusRepository(session, jobTypes = JobManagerIntegrationSpec.TestJobTypes)
  private lazy val lockRepository = new LockRepository(session, TestLockTypes)

  override protected def beforeEach(): Unit = await(lockRepository.clear())

  "JobManager locking" should {
    "should prevent 2 jobs sharing the same LockType from running in parallel" in {
      val cdl = new CountDownLatch(1)
      val mockedScheduler = mock[Scheduler]

      val manager = new JobManager(Seq(new Job1(jobStatusRepository, cdl), new Job12(jobStatusRepository)), lockRepository, jobStatusRepository, mock[ActorSystem], mockedScheduler, false)
      manager.triggerJob(JobType1)

      eventually {
        await(lockRepository.getIdForType(JobType1)) should be ('defined)
      }

      await(manager.triggerJob(JobType12)) should be (a[LockedStatus])
      cdl.countDown()
    }
  }
}

object JobManagerIntegrationSpec {
  class Job1(jobStatusRepository: JobStatusRepository, cdl: CountDownLatch) extends Job(JobType1, 3) {
    override def run()(implicit context: JobContext): Future[JobStartStatus] = {
      import scala.concurrent.ExecutionContext.Implicits.global
      Future {
        cdl.await()
        context.finishCallback()
      }
      Future.successful(Started(context.jobId))
    }

    override def cancel(): Unit = ???
  }

  object JobType12 extends JobType("testJob12", JobType1.lockType)

  class Job12(jobStatusRepository: JobStatusRepository) extends Job(JobType12, 3) {
    override def run()(implicit context: JobContext): Future[JobStartStatus] = {
      context.finishCallback()
      Future.successful(Started(context.jobId))
    }

    override def cancel(): Unit = ???
  }

  object TestJobTypes extends JobTypes {
    override protected def byName: PartialFunction[String, JobType] = {
      case JobType1.name => JobType1
      case JobType12.name => JobType12
    }
  }
}