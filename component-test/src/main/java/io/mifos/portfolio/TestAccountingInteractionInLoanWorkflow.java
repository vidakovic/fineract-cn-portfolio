/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.portfolio;

import com.google.gson.Gson;
import io.mifos.accounting.api.v1.domain.AccountType;
import io.mifos.accounting.api.v1.domain.Creditor;
import io.mifos.accounting.api.v1.domain.Debtor;
import io.mifos.core.api.util.ApiFactory;
import io.mifos.core.lang.DateConverter;
import io.mifos.individuallending.api.v1.domain.caseinstance.CaseParameters;
import io.mifos.individuallending.api.v1.domain.product.ChargeIdentifiers;
import io.mifos.individuallending.api.v1.domain.workflow.Action;
import io.mifos.individuallending.api.v1.events.IndividualLoanCommandEvent;
import io.mifos.individuallending.api.v1.events.IndividualLoanEventConstants;
import io.mifos.portfolio.api.v1.domain.Case;
import io.mifos.portfolio.api.v1.domain.ChargeDefinition;
import io.mifos.portfolio.api.v1.domain.CostComponent;
import io.mifos.portfolio.api.v1.domain.Product;
import io.mifos.portfolio.api.v1.events.ChargeDefinitionEvent;
import io.mifos.portfolio.api.v1.events.EventConstants;
import io.mifos.rhythm.spi.v1.client.BeatListener;
import io.mifos.rhythm.spi.v1.domain.BeatPublish;
import io.mifos.rhythm.spi.v1.events.BeatPublishEvent;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static io.mifos.portfolio.Fixture.MINOR_CURRENCY_UNIT_DIGITS;

/**
 * @author Myrle Krantz
 */
public class TestAccountingInteractionInLoanWorkflow extends AbstractPortfolioTest {
  private static final BigDecimal PROCESSING_FEE_AMOUNT = BigDecimal.valueOf(10_0000, MINOR_CURRENCY_UNIT_DIGITS);
  private static final BigDecimal LOAN_ORIGINATION_FEE_AMOUNT = BigDecimal.valueOf(100_0000, MINOR_CURRENCY_UNIT_DIGITS);
  private static final BigDecimal DISBURSEMENT_FEE_AMOUNT = BigDecimal.valueOf(1_0000, MINOR_CURRENCY_UNIT_DIGITS);

  private BeatListener portfolioBeatListener;

  private static Product product;
  private static Case customerCase;
  private static CaseParameters caseParameters;
  private static String pendingDisbursalAccountIdentifier;
  private static String customerLoanAccountIdentifier;


  @Before
  public void prepBeatListener() {
    portfolioBeatListener = new ApiFactory(logger).create(BeatListener.class, testEnvironment.serverURI());
  }

  @Test
  public void workflowTerminatingInApplicationDenial() throws InterruptedException {
    step1CreateProduct();
    step2CreateCase();
    step3OpenCase();
    step4DenyCase();
  }

  @Test
  public void workflowTerminatingInEarlyLoanPayoff() throws InterruptedException {
    step1CreateProduct();
    step2CreateCase();
    step3OpenCase();
    step4ApproveCase();
    step5DisburseFullAmount();
    step6CalculateInterestAccrual();
    //step7PaybackFullAmount();
  }

  //Create product and set charges to fixed fees.
  private void step1CreateProduct() throws InterruptedException {
    logger.info("step1CreateProduct");
    product = createProduct();

    setFeeToFixedValue(product.getIdentifier(), ChargeIdentifiers.PROCESSING_FEE_ID, PROCESSING_FEE_AMOUNT);
    setFeeToFixedValue(product.getIdentifier(), ChargeIdentifiers.LOAN_ORIGINATION_FEE_ID, LOAN_ORIGINATION_FEE_AMOUNT);
    setFeeToFixedValue(product.getIdentifier(), ChargeIdentifiers.DISBURSEMENT_FEE_ID, DISBURSEMENT_FEE_AMOUNT);

    final ChargeDefinition interestChargeDefinition = portfolioManager.getChargeDefinition(product.getIdentifier(), ChargeIdentifiers.INTEREST_ID);
    interestChargeDefinition.setAmount(Fixture.INTEREST_RATE);

    portfolioManager.changeChargeDefinition(product.getIdentifier(), interestChargeDefinition.getIdentifier(), interestChargeDefinition);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.PUT_CHARGE_DEFINITION,
        new ChargeDefinitionEvent(product.getIdentifier(), interestChargeDefinition.getIdentifier())));

    portfolioManager.enableProduct(product.getIdentifier(), true);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.PUT_PRODUCT_ENABLE, product.getIdentifier()));
  }

  private void step2CreateCase() throws InterruptedException {
    logger.info("step2CreateCase");
    caseParameters = Fixture.createAdjustedCaseParameters(x -> {
    });
    final String caseParametersAsString = new Gson().toJson(caseParameters);
    customerCase = createAdjustedCase(product.getIdentifier(), x -> x.setParameters(caseParametersAsString));

    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.OPEN);
    checkCostComponentForActionCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.OPEN,
        new CostComponent(ChargeIdentifiers.PROCESSING_FEE_ID, PROCESSING_FEE_AMOUNT));
  }

  //Open the case and accept a processing fee.
  private void step3OpenCase() throws InterruptedException {
    logger.info("step3OpenCase");
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.OPEN,
        Collections.singletonList(assignEntryToTeller()),
        IndividualLoanEventConstants.OPEN_INDIVIDUALLOAN_CASE,
        Case.State.PENDING);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.APPROVE, Action.DENY);
    checkCostComponentForActionCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.APPROVE,
        new CostComponent(ChargeIdentifiers.LOAN_ORIGINATION_FEE_ID, LOAN_ORIGINATION_FEE_AMOUNT),
        new CostComponent(ChargeIdentifiers.LOAN_FUNDS_ALLOCATION_ID, caseParameters.getMaximumBalance().setScale(product.getMinorCurrencyUnitDigits(), BigDecimal.ROUND_UNNECESSARY)));

    AccountingFixture.verifyTransfer(ledgerManager,
        AccountingFixture.TELLER_ONE_ACCOUNT_IDENTIFIER, AccountingFixture.PROCESSING_FEE_INCOME_ACCOUNT_IDENTIFIER,
        PROCESSING_FEE_AMOUNT
    );
  }


  //Deny the case. Once this is done, no more actions are possible for the case.
  private void step4DenyCase() throws InterruptedException {
    logger.info("step4DenyCase");
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.DENY,
        Collections.singletonList(assignEntryToTeller()),
        IndividualLoanEventConstants.DENY_INDIVIDUALLOAN_CASE,
        Case.State.CLOSED);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier());
  }


  //Approve the case, accept a loan origination fee, and prepare to disburse the loan by earmarking the funds.
  private void step4ApproveCase() throws InterruptedException {
    logger.info("step4ApproveCase");
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.APPROVE,
        Collections.singletonList(assignEntryToTeller()),
        IndividualLoanEventConstants.APPROVE_INDIVIDUALLOAN_CASE,
        Case.State.APPROVED);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.DISBURSE, Action.CLOSE);

    pendingDisbursalAccountIdentifier =
        AccountingFixture.verifyAccountCreation(ledgerManager, AccountingFixture.PENDING_DISBURSAL_LEDGER_IDENTIFIER, AccountType.ASSET);
    customerLoanAccountIdentifier =
        AccountingFixture.verifyAccountCreation(ledgerManager, AccountingFixture.CUSTOMER_LOAN_LEDGER_IDENTIFIER, AccountType.ASSET);

    final Set<Debtor> debtors = new HashSet<>();
    debtors.add(new Debtor(AccountingFixture.LOAN_FUNDS_SOURCE_ACCOUNT_IDENTIFIER, caseParameters.getMaximumBalance().toPlainString()));
    debtors.add(new Debtor(AccountingFixture.TELLER_ONE_ACCOUNT_IDENTIFIER, LOAN_ORIGINATION_FEE_AMOUNT.toPlainString()));

    final Set<Creditor> creditors = new HashSet<>();
    creditors.add(new Creditor(pendingDisbursalAccountIdentifier, caseParameters.getMaximumBalance().toPlainString()));
    creditors.add(new Creditor(AccountingFixture.LOAN_ORIGINATION_FEES_ACCOUNT_IDENTIFIER, LOAN_ORIGINATION_FEE_AMOUNT.toPlainString()));
    AccountingFixture.verifyTransfer(ledgerManager, debtors, creditors);
  }

  //Approve the case, accept a loan origination fee, and prepare to disburse the loan by earmarking the funds.
  private void step5DisburseFullAmount() throws InterruptedException {
    logger.info("step5DisburseFullAmount");
    checkStateTransfer(
        product.getIdentifier(),
        customerCase.getIdentifier(),
        Action.DISBURSE,
        Collections.singletonList(assignEntryToTeller()),
        IndividualLoanEventConstants.DISBURSE_INDIVIDUALLOAN_CASE,
        Case.State.ACTIVE);
    checkNextActionsCorrect(product.getIdentifier(), customerCase.getIdentifier(), Action.APPLY_INTEREST,
        Action.APPLY_INTEREST, Action.MARK_LATE, Action.ACCEPT_PAYMENT, Action.DISBURSE, Action.WRITE_OFF, Action.CLOSE);


    final Set<Debtor> debtors = new HashSet<>();
    debtors.add(new Debtor(pendingDisbursalAccountIdentifier, caseParameters.getMaximumBalance().toPlainString()));
    debtors.add(new Debtor(AccountingFixture.TELLER_ONE_ACCOUNT_IDENTIFIER, DISBURSEMENT_FEE_AMOUNT.toPlainString()));

    final Set<Creditor> creditors = new HashSet<>();
    creditors.add(new Creditor(customerLoanAccountIdentifier, caseParameters.getMaximumBalance().toPlainString()));
    creditors.add(new Creditor(AccountingFixture.DISBURSEMENT_FEE_INCOME_ACCOUNT_IDENTIFIER, DISBURSEMENT_FEE_AMOUNT.toPlainString()));
    AccountingFixture.verifyTransfer(ledgerManager, debtors, creditors);

  }

  //Perform daily interest calculation.
  private void step6CalculateInterestAccrual() throws InterruptedException {
    logger.info("step6CalculateInterestAccrual");
    final String beatIdentifier = "alignment0";
    final String midnightTimeStamp = DateConverter.toIsoString(LocalDateTime.now().truncatedTo(ChronoUnit.DAYS));

    AccountingFixture.mockBalance(customerLoanAccountIdentifier, caseParameters.getMaximumBalance());

    final BeatPublish interestBeat = new BeatPublish(beatIdentifier, midnightTimeStamp);
    portfolioBeatListener.publishBeat(interestBeat);
    Assert.assertTrue(this.eventRecorder.wait(io.mifos.rhythm.spi.v1.events.EventConstants.POST_PUBLISHEDBEAT,
        new BeatPublishEvent(EventConstants.DESTINATION, beatIdentifier, midnightTimeStamp)));

    Assert.assertTrue(eventRecorder.wait(IndividualLoanEventConstants.APPLY_INTEREST_INDIVIDUALLOAN_CASE,
        new IndividualLoanCommandEvent(product.getIdentifier(), customerCase.getIdentifier())));

    final Case customerCaseAfterStateChange = portfolioManager.getCase(product.getIdentifier(), customerCase.getIdentifier());
    Assert.assertEquals(customerCaseAfterStateChange.getCurrentState(), Case.State.ACTIVE.name());

    final String calculatedInterest = caseParameters.getMaximumBalance().multiply(Fixture.INTEREST_RATE.divide(Fixture.ACCRUAL_PERIODS, 8, BigDecimal.ROUND_HALF_EVEN))
        .setScale(MINOR_CURRENCY_UNIT_DIGITS, BigDecimal.ROUND_HALF_EVEN)
        .toPlainString();

    final Set<Debtor> debtors = new HashSet<>();
    debtors.add(new Debtor(
        AccountingFixture.LOAN_INTEREST_ACCRUAL_ACCOUNT,
        calculatedInterest));

    final Set<Creditor> creditors = new HashSet<>();
    creditors.add(new Creditor(
        customerLoanAccountIdentifier,
        calculatedInterest));
    AccountingFixture.verifyTransfer(ledgerManager, debtors, creditors);
  }
}