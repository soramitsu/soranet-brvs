package iroha.validation.rules.impl;

import iroha.protocol.Commands.Command;
import iroha.protocol.TransactionOuterClass.Transaction;
import iroha.validation.rules.Rule;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TransferTxVolumeRule implements Rule {

  private String asset;
  private BigDecimal limit;

  @Autowired
  public TransferTxVolumeRule(String asset, BigDecimal limit) {
    this.asset = asset;
    this.limit = limit;
  }

  @Override
  public boolean isSatisfiedBy(Transaction transaction) {
    return transaction
        .getPayload()
        .getReducedPayload()
        .getCommandsList()
        .stream()
        .filter(Command::hasTransferAsset)
        .filter(transfer -> transfer.getTransferAsset().getAssetId().equals(asset))
        .noneMatch(transfer -> new BigDecimal(transfer.getTransferAsset().getAmount())
            .compareTo(limit) > 0);
  }
}