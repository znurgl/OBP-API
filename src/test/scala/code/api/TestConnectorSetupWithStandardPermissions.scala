package code.api

import bootstrap.liftweb.ToSchemify
import code.model.dataAccess._
import code.model._
import code.views.Views
import net.liftweb.mapper.MetaMapper
import net.liftweb.mongodb._
import net.liftweb.util.Helpers._

/**
 * Handles setting up views and permissions and account holders using ViewImpls, ViewPrivileges,
 * and MappedAccountHolder
 */
trait TestConnectorSetupWithStandardPermissions extends TestConnectorSetup {

  override protected def setAccountHolder(user: User, bankId : BankId, accountId : AccountId) = {
    MappedAccountHolder.createMappedAccountHolder(user.apiId.value, bankId.value, accountId.value, "TestConnectorSetupWithStandardPermissions")
  }

  override protected def grantAccessToAllExistingViews(user : User) = {
    Views.views.vend.grantAccessToAllExistingViews(user)
  }

  override protected def grantAccessToView(user : User, view : View) = {
    Views.views.vend.grantAccessToView(user, view)
  }

  protected def createOwnerView(bankId: BankId, accountId: AccountId ) : View = {
    Views.views.vend.createOwnerView(bankId, accountId, randomString(3))
  }

  protected def createPublicView(bankId: BankId, accountId: AccountId) : View = {
    Views.views.vend.createPublicView(bankId, accountId, randomString(3))
  }

  protected def createRandomView(bankId: BankId, accountId: AccountId) : View = {
    Views.views.vend.createRandomView(bankId, accountId)
  }


  protected def wipeTestData(): Unit = {

    //drop the mongo Database after each test
    MongoDB.getDb(DefaultMongoIdentifier).foreach(_.dropDatabase())

    //returns true if the model should not be wiped after each test
    def exclusion(m : MetaMapper[_]) = {
      m == Nonce || m == Token || m == Consumer || m == OBPUser || m == APIUser
    }

    //empty the relational db tables after each test
    ToSchemify.models.filterNot(exclusion).foreach(_.bulkDelete_!!())
  }
}
