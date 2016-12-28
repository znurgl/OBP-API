package code.views

import code.api.APIFailure
import code.bankconnectors.Connector
import code.model.dataAccess.{ViewImpl, ViewPrivileges}
import code.model.{User, Permission, CreateViewJSON, UpdateViewJSON, _}
import net.liftweb.common._
import net.liftweb.mapper.By
import net.liftweb.util.Helpers._

import scala.collection.immutable.List


//TODO: Replace BankAccounts with bankPermalink + accountPermalink


object MapperViews extends Views with Loggable {

  def permissions(account : BankAccount) : List[Permission] = {

    val views: List[ViewImpl] = ViewImpl.findAll(By(ViewImpl.isPublic_, false) ::
      ViewImpl.accountFilter(account.bankId, account.accountId): _*)
    //all the user that have access to at least to a view
    val users = views.map(_.users).flatten.distinct
    val usersPerView = views.map(v  =>(v, v.users))
    val permissions = users.map(u => {
      new Permission(
        u,
        usersPerView.filter(_._2.contains(u)).map(_._1)
      )
    })

    permissions
  }

  def permission(account: BankAccount, user: User): Box[Permission] = {

    //search ViewPrivileges to get all views for user and then filter the views
    // by bankPermalink and accountPermalink
    //TODO: do it in a single query with a join
    val privileges = ViewPrivileges.findAll(By(ViewPrivileges.user, user.apiId.value))
    val views = privileges.flatMap(_.view.obj).filter(v => {
      v.accountId == account.accountId &&
        v.bankId == account.bankId
    })
    Full(Permission(user, views))
  }

  def addPermission(viewUID: ViewUID, user: User): Box[View] = {
    logger.debug(s"addPermission says viewUID is $viewUID user is $user")
    val viewImpl = ViewImpl.find(viewUID)

    viewImpl match {
      case Full(vImpl) => {
        if (ViewPrivileges.count(By(ViewPrivileges.user, user.apiId.value), By(ViewPrivileges.view, vImpl.id)) == 0) {
          val saved = ViewPrivileges.create.
            user(user.apiId.value).
            view(vImpl.id).
            save

          if (saved) Full(vImpl)
          else {
            logger.info("failed to save ViewPrivileges")
            Empty ~> APIFailure("Server error adding permission", 500) //TODO: move message + code logic to api level
          }
        } else Full(vImpl) //privilege already exists, no need to create one
      }
      case _ => {
        Empty ~> APIFailure(s"View $viewUID. not found", 404) //TODO: move message + code logic to api level
      }
    }
  }

  def addPermissions(views: List[ViewUID], user: User): Box[List[View]] = {
    val viewImpls = views.map(uid => ViewImpl.find(uid)).collect { case Full(v) => v}

    if (viewImpls.size != views.size) {
      val failMsg = s"not all viewimpls could be found for views $viewImpls"
      logger.info(failMsg)
      Failure(failMsg) ~>
        APIFailure(s"One or more views not found", 404) //TODO: this should probably be a 400, but would break existing behaviour
      //TODO: APIFailures with http response codes belong at a higher level in the code
    } else {
      viewImpls.foreach(v => {
        if (ViewPrivileges.count(By(ViewPrivileges.user, user.apiId.value), By(ViewPrivileges.view, v.id)) == 0) {
          ViewPrivileges.create.
            user(user.apiId.value).
            view(v.id).
            save
        }
      })
      //TODO: this doesn't handle the case where one viewImpl fails to be saved
      Full(viewImpls)
    }
  }

  def revokePermission(viewUID : ViewUID, user : User) : Box[Boolean] = {
    for{
      viewImpl <- ViewImpl.find(viewUID)
      vp <- ViewPrivileges.find(By(ViewPrivileges.user, user.apiId.value), By(ViewPrivileges.view, viewImpl.id))
      deletable <- accessRemovableAsBox(viewImpl, user)
    } yield {
      vp.delete_!
    }
  }

  //returns Full if deletable, Failure if not
  def accessRemovableAsBox(viewImpl : ViewImpl, user : User) : Box[Unit] = {
    if(accessRemovable(viewImpl, user)) Full(Unit)
    else Failure("access cannot be revoked")
  }


  def accessRemovable(viewImpl: ViewImpl, user : User) : Boolean = {
    if(viewImpl.viewId == ViewId("owner")) {

      //if the user is an account holder, we can't revoke access to the owner view
      if(Connector.connector.vend.getAccountHolders(viewImpl.bankId, viewImpl.accountId).contains(user)) {
        false
      } else {
        // if it's the owner view, we can only revoke access if there would then still be someone else
        // with access
        viewImpl.users.length > 1
      }

    } else true
  }




  /*
  This removes the link between a User and a View (View Privileges)
   */

  def revokeAllPermission(bankId : BankId, accountId: AccountId, user : User) : Box[Boolean] = {
    //TODO: make this more efficient by using one query (with a join)
    val allUserPrivs = ViewPrivileges.findAll(By(ViewPrivileges.user, user.apiId.value))

    val relevantAccountPrivs = allUserPrivs.filter(p => p.view.obj match {
      case Full(v) => {
        v.bankId == bankId && v.accountId == accountId
      }
      case _ => false
    })

    val allRelevantPrivsRevokable = relevantAccountPrivs.forall( p => p.view.obj match {
      case Full(v) => accessRemovable(v, user)
      case _ => false
    })


    if(allRelevantPrivsRevokable) {
      relevantAccountPrivs.foreach(_.delete_!)
      Full(true)
    } else {
      Failure("One of the views this user has access to is the owner view, and there would be no one with access" +
        " to this owner view if access to the user was revoked. No permissions to any views on the account have been revoked.")
    }

  }

  def view(viewId : ViewId, account: BankAccount) : Box[View] = {
    view(ViewUID(viewId, account.bankId, account.accountId))
  }

  def view(viewUID : ViewUID) : Box[View] = {
    ViewImpl.find(viewUID)
  }

  /*
  Create View based on the Specification (name, alias behavior, what fields can be seen, actions are allowed etc. )
  * */
  def createView(bankAccount: BankAccount, view: CreateViewJSON): Box[View] = {
    if(view.name.contentEquals("")) {
      return Failure("You cannot create a View with an empty Name")
    }

    val newViewPermalink = {
      view.name.replaceAllLiterally(" ", "").toLowerCase
    }

    val existing = ViewImpl.count(
      By(ViewImpl.permalink_, newViewPermalink) ::
        ViewImpl.accountFilter(bankAccount.bankId, bankAccount.accountId): _*
    ) == 1

    if (existing)
      Failure(s"There is already a view with permalink $newViewPermalink on this bank account")
    else {
      val createdView = ViewImpl.create.
        name_(view.name).
        permalink_(newViewPermalink).
        bankPermalink(bankAccount.bankId.value).
        accountPermalink(bankAccount.accountId.value)

      createdView.setFromViewData(view)
      Full(createdView.saveMe)
    }
  }


  /* Update the specification of the view (what data/actions are allowed) */
  def updateView(bankAccount : BankAccount, viewId: ViewId, viewUpdateJson : UpdateViewJSON) : Box[View] = {

    for {
      view <- ViewImpl.find(viewId, bankAccount)
    } yield {
      view.setFromViewData(viewUpdateJson)
      view.saveMe
    }
  }

  def removeView(viewId: ViewId, bankAccount: BankAccount): Box[Unit] = {

    if(viewId.value == "owner")
      Failure("you cannot delete the owner view")
    else {
      for {
        view <- ViewImpl.find(viewId, bankAccount)
        if(view.delete_!)
      } yield {
      }
    }
  }

  def views(bankAccount : BankAccount) : List[View] = {
    ViewImpl.findAll(ViewImpl.accountFilter(bankAccount.bankId, bankAccount.accountId): _*)
  }

  def permittedViews(user: User, bankAccount: BankAccount): List[View] = {
    //TODO: do this more efficiently?
    val allUserPrivs = ViewPrivileges.findAll(By(ViewPrivileges.user, user.apiId.value))
    val userNonPublicViewsForAccount = allUserPrivs.flatMap(p => {
      p.view.obj match {
        case Full(v) => if(
          !v.isPublic &&
            v.bankId == bankAccount.bankId&&
            v.accountId == bankAccount.accountId){
          Some(v)
        } else None
        case _ => None
      }
    })
    userNonPublicViewsForAccount ++ bankAccount.publicViews
  }

  def publicViews(bankAccount : BankAccount) : List[View] = {
    //TODO: do this more efficiently?
    ViewImpl.findAll(ViewImpl.accountFilter(bankAccount.bankId, bankAccount.accountId): _*).filter(v => {
      v.isPublic == true
    })
  }

  def getAllPublicAccounts() : List[BankAccount] = {
    //TODO: do this more efficiently

    // An account is considered public if it contains a public view

    val bankAndAccountIds : List[(BankId, AccountId)] =
      ViewImpl.findAll(By(ViewImpl.isPublic_, true)).map(v =>
        (v.bankId, v.accountId)
      ).distinct //we remove duplicates here

    val accountsList = bankAndAccountIds.map {
      case (bankId, accountId) => {
        (bankId, accountId)
      }
    }
    Connector.connector.vend.getBankAccounts(accountsList)
  }

  def getPublicBankAccounts(bank : Bank) : List[BankAccount] = {
    //TODO: do this more efficiently

    val accountIds : List[AccountId] =
      ViewImpl.findAll(By(ViewImpl.isPublic_, true), By(ViewImpl.bankPermalink, bank.bankId.value)).map(v => {
        v.accountId
      }).distinct //we remove duplicates here

    val accountsList = accountIds.map(accountId => {
      (bank.bankId, accountId)
    })
    Connector.connector.vend.getBankAccounts(accountsList)
  }

  /**
   * @param user
   * @return the bank accounts the @user can see (public + private if @user is Full, public if @user is Empty)
   */
  def getAllAccountsUserCanSee(user : Box[User]) : List[BankAccount] = {
    user match {
      case Full(theuser) => {
        //TODO: this could be quite a bit more efficient...

        val publicViewBankAndAccountIds= ViewImpl.findAll(By(ViewImpl.isPublic_, true)).map(v => {
          (v.bankId, v.accountId)
        }).distinct

        val userPrivileges : List[ViewPrivileges] = ViewPrivileges.findAll(By(ViewPrivileges.user, theuser.apiId.value))
        val userNonPublicViews : List[ViewImpl] = userPrivileges.map(_.view.obj).flatten.filter(!_.isPublic)

        val nonPublicViewBankAndAccountIds = userNonPublicViews.map(v => {
          (v.bankId, v.accountId)
        }).distinct //we remove duplicates here

        val visibleBankAndAccountIds =
          (publicViewBankAndAccountIds ++ nonPublicViewBankAndAccountIds).distinct

        val accountsList = visibleBankAndAccountIds.map {
          case (bankId, accountId) => {
            (bankId, accountId)
          }
        }
        Connector.connector.vend.getBankAccounts(accountsList)
      }
      case _ => getAllPublicAccounts()
    }
  }

  /**
   * @param user
   * @return the bank accounts at @bank the @user can see (public + private if @user is Full, public if @user is Empty)
   */
  def getAllAccountsUserCanSee(bank: Bank, user : Box[User]) : List[BankAccount] = {
    user match {
      case Full(theuser) => {
        //TODO: this could be quite a bit more efficient...

        val publicViewBankAndAccountIds = ViewImpl.findAll(By(ViewImpl.isPublic_, true),
          By(ViewImpl.bankPermalink, bank.bankId.value)).map(v => {
          (v.bankId, v.accountId)
        }).distinct

        val userPrivileges : List[ViewPrivileges] = ViewPrivileges.findAll(By(ViewPrivileges.user, theuser.apiId.value))
        val userNonPublicViews : List[ViewImpl] = userPrivileges.map(_.view.obj).flatten.filter(v => {
          !v.isPublic && v.bankId == bank.bankId
        })

        val nonPublicViewBankAndAccountIds = userNonPublicViews.map(v => {
          (v.bankId, v.accountId)
        }).distinct //we remove duplicates here

        val visibleBankAndAccountIds =
          (publicViewBankAndAccountIds ++ nonPublicViewBankAndAccountIds).distinct

        val accountsList = visibleBankAndAccountIds.map {
          case (bankId, accountId) => {
            (bankId, accountId)
          }
        }
        Connector.connector.vend.getBankAccounts(accountsList)
      }
      case _ => getPublicBankAccounts(bank)
    }
  }

  /**
   * @return the bank accounts where the user has at least access to a non public view (is_public==false)
   */
  def getNonPublicBankAccounts(user : User) :  List[BankAccount] = {
    //TODO: make this more efficient
    val userPrivileges : List[ViewPrivileges] = ViewPrivileges.findAll(By(ViewPrivileges.user, user.apiId.value))
    val userNonPublicViews : List[ViewImpl] = userPrivileges.map(_.view.obj).flatten.filter(!_.isPublic)

    val nonPublicViewBankAndAccountIds = userNonPublicViews.map(v => {
      (v.bankId, v.accountId)
    }).distinct //we remove duplicates here

    val accountsList = nonPublicViewBankAndAccountIds.map {
      case(bankId, accountId) => {
        (bankId, accountId)
      }
    }
    Connector.connector.vend.getBankAccounts(accountsList)
  }

  /**
   * @return the bank accounts where the user has at least access to a non public view (is_public==false) for a specific bank
   */
  def getNonPublicBankAccounts(user : User, bankId : BankId) :  List[BankAccount] = {
    val userPrivileges : List[ViewPrivileges] = ViewPrivileges.findAll(By(ViewPrivileges.user, user.apiId.value))
    val userNonPublicViewsForBank : List[ViewImpl] =
      userPrivileges.map(_.view.obj).flatten.filter(v => !v.isPublic && v.bankId == bankId)

    val nonPublicViewAccountIds = userNonPublicViewsForBank.
      map(_.accountId).distinct //we remove duplicates here

    val accountsList = nonPublicViewAccountIds.map { accountId =>
        (bankId, accountId)
    }
    Connector.connector.vend.getBankAccounts(accountsList)
  }

  def grantAccessToView(user : User, view : View) = {
    val viewImpl = ViewImpl.find(view.uid)
    ViewPrivileges.create.
      view(viewImpl.get). //explodes if no viewImpl exists, but that's okay, the test should fail then
      user(user.apiId.value).
      save
  }

  def createOwnerView(bankId: BankId, accountId: AccountId, description: String = "Owner View") : View = {
    ViewImpl.createAndSaveOwnerView(bankId, accountId, description)
  }

  def createPublicView(bankId: BankId, accountId: AccountId, description: String = "Public View") : View = {
    ViewImpl.createAndSaveDefaultPublicView(bankId, accountId, description)
  }

  def createAccountantsView(bankId: BankId, accountId: AccountId, description: String = "Accountants View") : View = {
    ViewImpl.createAndSaveDefaultAccountantsView(bankId, accountId, description)
  }

  def createAuditorsView(bankId: BankId, accountId: AccountId, description: String = "Auditors View") : View = {
    ViewImpl.createAndSaveDefaultAuditorsView(bankId, accountId, description)
  }

  def createRandomView(bankId: BankId, accountId: AccountId) : View = {
    ViewImpl.create.
      name_(randomString(5)).
      description_(randomString(3)).
      permalink_(randomString(3)).
      isPublic_(false).
      bankPermalink(bankId.value).
      accountPermalink(accountId.value).
      usePrivateAliasIfOneExists_(false).
      usePublicAliasIfOneExists_(false).
      hideOtherAccountMetadataIfAlias_(false).
      canSeeTransactionThisBankAccount_(true).
      canSeeTransactionOtherBankAccount_(true).
      canSeeTransactionMetadata_(true).
      canSeeTransactionDescription_(true).
      canSeeTransactionAmount_(true).
      canSeeTransactionType_(true).
      canSeeTransactionCurrency_(true).
      canSeeTransactionStartDate_(true).
      canSeeTransactionFinishDate_(true).
      canSeeTransactionBalance_(true).
      canSeeComments_(true).
      canSeeOwnerComment_(true).
      canSeeTags_(true).
      canSeeImages_(true).
      canSeeBankAccountOwners_(true).
      canSeeBankAccountType_(true).
      canSeeBankAccountBalance_(true).
      canSeeBankAccountCurrency_(true).
      canSeeBankAccountLabel_(true).
      canSeeBankAccountNationalIdentifier_(true).
      canSeeBankAccountSwift_bic_(true).
      canSeeBankAccountIban_(true).
      canSeeBankAccountNumber_(true).
      canSeeBankAccountBankName_(true).
      canSeeBankAccountBankPermalink_(true).
      canSeeOtherAccountNationalIdentifier_(true).
      canSeeOtherAccountSWIFT_BIC_(true).
      canSeeOtherAccountIBAN_ (true).
      canSeeOtherAccountBankName_(true).
      canSeeOtherAccountNumber_(true).
      canSeeOtherAccountMetadata_(true).
      canSeeOtherAccountKind_(true).
      canSeeMoreInfo_(true).
      canSeeUrl_(true).
      canSeeImageUrl_(true).
      canSeeOpenCorporatesUrl_(true).
      canSeeCorporateLocation_(true).
      canSeePhysicalLocation_(true).
      canSeePublicAlias_(true).
      canSeePrivateAlias_(true).
      canAddMoreInfo_(true).
      canAddURL_(true).
      canAddImageURL_(true).
      canAddOpenCorporatesUrl_(true).
      canAddCorporateLocation_(true).
      canAddPhysicalLocation_(true).
      canAddPublicAlias_(true).
      canAddPrivateAlias_(true).
      canDeleteCorporateLocation_(true).
      canDeletePhysicalLocation_(true).
      canEditOwnerComment_(true).
      canAddComment_(true).
      canDeleteComment_(true).
      canAddTag_(true).
      canDeleteTag_(true).
      canAddImage_(true).
      canDeleteImage_(true).
      canAddWhereTag_(true).
      canSeeWhereTag_(true).
      canDeleteWhereTag_(true).
      saveMe
  }

  //TODO This is used only for tests, but might impose security problem
  def grantAccessToAllExistingViews(user : User) = {
    ViewImpl.findAll.foreach(v => {
      ViewPrivileges.create.
        view(v).
        user(user.apiId.value).
        save
    })
    true
  }


  def viewExists(bankId: BankId, accountId: AccountId, name: String): Boolean = {
    val res =
      ViewImpl.findAll(
        By(ViewImpl.bankPermalink, bankId.value),
        By(ViewImpl.accountPermalink, accountId.value),
        By(ViewImpl.name_, name)
      )
    res.nonEmpty
  }


  def removeAllPermissions(bankId: BankId, accountId: AccountId) : Boolean = {
    val views = ViewImpl.findAll(
      By(ViewImpl.bankPermalink, bankId.value),
      By(ViewImpl.accountPermalink, accountId.value)
    )
    var privilegesDeleted = true
    views.map (x => {
      privilegesDeleted &&= ViewPrivileges.bulkDelete_!!(By(ViewPrivileges.view, x.id_))
    } )
      privilegesDeleted
  }


  def removeAllViews(bankId: BankId, accountId: AccountId) : Boolean = {
    ViewImpl.bulkDelete_!!(
      By(ViewImpl.bankPermalink, bankId.value),
      By(ViewImpl.accountPermalink, accountId.value)
    )
  }

}
