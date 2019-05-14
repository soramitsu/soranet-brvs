package iroha.validation.rules.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import iroha.protocol.Commands.Command;
import iroha.protocol.Commands.TransferAsset;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import iroha.validation.rules.impl.billing.BillingRule;
import iroha.validation.verdict.Verdict;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {

  private String asset;
  private Transaction transaction;
  private TransferAsset transferAsset;
  private List<Command> commands;
  private Rule rule;

  private void init() {
    asset = "asset";
    // transfer mock
    transaction = mock(Transaction.class, RETURNS_DEEP_STUBS);
    transferAsset = mock(TransferAsset.class);

    when(transferAsset.getSrcAccountId()).thenReturn("user@users");
    when(transferAsset.getDestAccountId()).thenReturn("destination@users");

    final Command command = mock(Command.class);

    when(command.hasTransferAsset()).thenReturn(true);
    when(command.getTransferAsset()).thenReturn(transferAsset);

    commands = Collections.singletonList(command);

    when(transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList())
        .thenReturn(commands);
  }

  private void initTransferTxVolumeTest() {
    init();
    rule = new TransferTxVolumeRule(asset, BigDecimal.TEN);
  }

  private void initBillingTest() throws IOException {
    init();
    when(transaction.getPayload().getReducedPayload().getCreatorAccountId())
        .thenReturn("user@users");
    when(transaction.getPayload().getBatch().getReducedHashesCount()).thenReturn(1);
    rule = new BillingRule("url",
        "rmqHost",
        1,
        "exchange",
        "key",
        "users",
        "deposit@users",
        "withdrawal@users"
    ) {
      @Override
      protected void runCacheUpdater() {
      }
    };
  }

  /**
   * @given {@link SampleRule} instance
   * @when any {@link Transaction} is passed to the rule satisfiability method
   * @then {@link SampleRule} is satisfied by the {@link Transaction}
   */
  @Test
  void sampleRuleTest() {
    rule = new SampleRule();
    // any transaction
    transaction = mock(Transaction.class);
    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link TransferTxVolumeRule} instance with limit of asset amount equal to 10 and for the
   * asset called "asset"
   * @when {@link Transaction} with {@link Command TransferAsset} command of 1 "asset" passed
   * @then {@link TransferTxVolumeRule} is satisfied by such {@link Transaction}
   */
  @Test
  void correctTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.ONE.toPlainString());

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link TransferTxVolumeRule} instance with limit of asset amount equal to 10 and for the
   * asset called "asset"
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "otherAsset"
   * passed
   * @then {@link TransferTxVolumeRule} is satisfied by such {@link Transaction}
   */
  @Test
  void otherAssetTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn("otherAsset");
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link TransferTxVolumeRule} instance with limit of asset amount equal to 10 and for the
   * asset called "asset"
   * @when {@link Transaction} with {@link Command TransferAsset} command of 100 "asset" passed
   * @then {@link TransferTxVolumeRule} is NOT satisfied by such {@link Transaction}
   */
  @Test
  void violatedTransferTxVolumeRuleTest() {
    initTransferTxVolumeTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link BillingRule} instance with no billing data
   * @when {@link Transaction} with the only {@link Command TransferAsset} command of 100 "asset"
   * passed
   * @then {@link BillingRule} is satisfied by such {@link Transaction}
   */
  @Test
  void emptyBillingRuleGoodTest() throws IOException {
    initBillingTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());

    assertEquals(Verdict.VALIDATED, rule.isSatisfiedBy(transaction).getStatus());
  }

  /**
   * @given {@link BillingRule} instance with no billing data
   * @when {@link Transaction} with the only {@link Command TransferAsset} command to destination of
   * a billing account
   * @then {@link BillingRule} is satisfied by such {@link Transaction}
   */
  @Test
  void emptyBillingRuleBadTest() throws IOException {
    initBillingTest();

    when(transferAsset.getAssetId()).thenReturn(asset);
    when(transferAsset.getAmount()).thenReturn(BigDecimal.valueOf(100).toPlainString());
    when(transferAsset.getDestAccountId()).thenReturn("transfer_billing@users");

    assertEquals(Verdict.REJECTED, rule.isSatisfiedBy(transaction).getStatus());
  }
}