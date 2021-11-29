/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.core.bc;

import co.rsk.core.Coin;
import co.rsk.core.RskAddress;
import co.rsk.core.TransactionExecutorFactory;
import co.rsk.crypto.Keccak256;
import co.rsk.db.RepositoryLocator;
import co.rsk.metrics.profilers.Metric;
import co.rsk.metrics.profilers.Profiler;
import co.rsk.metrics.profilers.ProfilerFactory;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.*;
import org.ethereum.vm.DataWord;
import org.ethereum.vm.PrecompiledContracts;
import org.ethereum.vm.program.ProgramResult;
import org.ethereum.vm.trace.ProgramTraceProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ethereum.config.blockchain.upgrades.ConsensusRule.RSKIP126;
import static org.ethereum.config.blockchain.upgrades.ConsensusRule.RSKIP85;

/**
 * This is a stateless class with methods to execute blocks with its transactions.
 * There are two main use cases:
 * - execute and validate the block final state
 * - execute and complete the block final state
 *
 * Note that this class IS NOT guaranteed to be thread safe because its dependencies might hold state.
 */
public class BlockExecutor {
    private static final Logger logger = LoggerFactory.getLogger("blockexecutor");
    private static final Profiler profiler = ProfilerFactory.getInstance();

    private final RepositoryLocator repositoryLocator;
    private final TransactionExecutorFactory transactionExecutorFactory;
    private final ActivationConfig activationConfig;

    private final Map<Keccak256, ProgramResult> transactionResults = new HashMap<>();
    private boolean registerProgramResults;

    public BlockExecutor(
            ActivationConfig activationConfig,
            RepositoryLocator repositoryLocator,
            TransactionExecutorFactory transactionExecutorFactory) {
        this.repositoryLocator = repositoryLocator;
        this.transactionExecutorFactory = transactionExecutorFactory;
        this.activationConfig = activationConfig;
    }

    /**
     * Execute and complete a block.
     *
     * @param block        A block to execute and complete
     * @param parent       The parent of the block.
     */
    public BlockResult executeAndFill(Block block, BlockHeader parent) {
        BlockResult result = execute(block, parent, true, false);
        fill(block, result);
        return result;
    }

    @VisibleForTesting
    public void executeAndFillAll(Block block, BlockHeader parent) {
        BlockResult result = execute(block, parent, false, true);
        fill(block, result);
    }

    @VisibleForTesting
    public void executeAndFillReal(Block block, BlockHeader parent) {
        BlockResult result = execute(block, parent, false, false);
        if (result != BlockResult.INTERRUPTED_EXECUTION_BLOCK_RESULT) {
            fill(block, result);
        }
    }

    private void fill(Block block, BlockResult result) {
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.FILLING_EXECUTED_BLOCK);
        BlockHeader header = block.getHeader();
        block.setTransactionsList(result.getExecutedTransactions());
        boolean isRskip126Enabled = activationConfig.isActive(RSKIP126, block.getNumber());
        header.setTransactionsRoot(BlockHashesHelper.getTxTrieRoot(block.getTransactionsList(), isRskip126Enabled));
        header.setReceiptsRoot(BlockHashesHelper.calculateReceiptsTrieRoot(result.getTransactionReceipts(), isRskip126Enabled));
        header.setStateRoot(result.getFinalState().getHash().getBytes());
        header.setGasUsed(result.getGasUsed());
        header.setPaidFees(result.getPaidFees());
        header.setLogsBloom(calculateLogsBloom(result.getTransactionReceipts()));

        block.flushRLP();
        profiler.stop(metric);
    }

    /**
     * Execute and validate the final state of a block.
     *
     * @param block        A block to execute and complete
     * @param parent       The parent of the block.
     * @return true if the block final state is equalBytes to the calculated final state.
     */
    @VisibleForTesting
    public boolean executeAndValidate(Block block, BlockHeader parent) {
        BlockResult result = execute(block, parent, false, false);

        return this.validate(block, result);
    }

    /**
     * Validate the final state of a block.
     *
     * @param block        A block to validate
     * @param result       A block result (state root, receipts root, etc...)
     * @return true if the block final state is equalBytes to the calculated final state.
     */
    public boolean validate(Block block, BlockResult result) {
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.BLOCK_FINAL_STATE_VALIDATION);
        if (result == BlockResult.INTERRUPTED_EXECUTION_BLOCK_RESULT) {
            logger.error("Block {} [{}] execution was interrupted because of an invalid transaction", block.getNumber(), block.getPrintableHash());
            profiler.stop(metric);
            return false;
        }

        boolean isValidStateRoot = validateStateRoot(block.getHeader(), result);
        if (!isValidStateRoot) {
            logger.error("Block {} [{}] given State Root is invalid", block.getNumber(), block.getPrintableHash());
            profiler.stop(metric);
            return false;
        }

        boolean isValidReceiptsRoot = validateReceiptsRoot(block.getHeader(), result);
        if (!isValidReceiptsRoot) {
            logger.error("Block {} [{}] given Receipt Root is invalid", block.getNumber(), block.getPrintableHash());
            profiler.stop(metric);
            return false;
        }

        boolean isValidLogsBloom = validateLogsBloom(block.getHeader(), result);
        if (!isValidLogsBloom) {
            logger.error("Block {} [{}] given Logs Bloom is invalid", block.getNumber(), block.getPrintableHash());
            profiler.stop(metric);
            return false;
        }

        if (result.getGasUsed() != block.getGasUsed()) {
            logger.error("Block {} [{}] given gasUsed doesn't match: {} != {}", block.getNumber(), block.getPrintableHash(), block.getGasUsed(), result.getGasUsed());
            profiler.stop(metric);
            return false;
        }

        Coin paidFees = result.getPaidFees();
        Coin feesPaidToMiner = block.getFeesPaidToMiner();

        if (!paidFees.equals(feesPaidToMiner))  {
            logger.error("Block {} [{}] given paidFees doesn't match: {} != {}", block.getNumber(), block.getPrintableHash(), feesPaidToMiner, paidFees);
            profiler.stop(metric);
            return false;
        }

        List<Transaction> executedTransactions = result.getExecutedTransactions();
        List<Transaction> transactionsList = block.getTransactionsList();

        if (!executedTransactions.equals(transactionsList))  {
            logger.error("Block {} [{}] given txs doesn't match: {} != {}", block.getNumber(), block.getPrintableHash(), transactionsList, executedTransactions);
            profiler.stop(metric);
            return false;
        }

        profiler.stop(metric);
        return true;
    }

    @VisibleForTesting
    boolean validateStateRoot(BlockHeader header, BlockResult result) {
        boolean isRskip85Enabled = activationConfig.isActive(RSKIP85, header.getNumber());
        if (!isRskip85Enabled) {
            return true;
        }

        boolean isRskip126Enabled = activationConfig.isActive(RSKIP126, header.getNumber());
        if (!isRskip126Enabled) {
            return true;
        }

        // we only validate state roots of blocks after RSKIP 126 activation
        return Arrays.equals(result.getFinalState().getHash().getBytes(), header.getStateRoot());
    }

    private boolean validateReceiptsRoot(BlockHeader header, BlockResult result) {
        boolean isRskip126Enabled = activationConfig.isActive(RSKIP126, header.getNumber());
        byte[] receiptsTrieRoot = BlockHashesHelper.calculateReceiptsTrieRoot(result.getTransactionReceipts(), isRskip126Enabled);
        return Arrays.equals(receiptsTrieRoot, header.getReceiptsRoot());
    }

    private boolean validateLogsBloom(BlockHeader header, BlockResult result) {
        return Arrays.equals(calculateLogsBloom(result.getTransactionReceipts()), header.getLogsBloom());
    }

    @VisibleForTesting
    public BlockResult execute(Block block, BlockHeader parent, boolean discardInvalidTxs) {
        return execute(block, parent, discardInvalidTxs, false);
    }

    public BlockResult execute(Block block, BlockHeader parent, boolean discardInvalidTxs, boolean ignoreReadyToExecute) {
        return executeInternal(null, 0, block, parent, discardInvalidTxs, ignoreReadyToExecute);
    }

    /**
     * Execute a block while saving the execution trace in the trace processor
     */
    public void traceBlock(
            ProgramTraceProcessor programTraceProcessor,
            int vmTraceOptions,
            Block block,
            BlockHeader parent,
            boolean discardInvalidTxs,
            boolean ignoreReadyToExecute) {
        executeInternal(
                Objects.requireNonNull(programTraceProcessor), vmTraceOptions, block, parent, discardInvalidTxs, ignoreReadyToExecute
        );
    }

    private BlockResult executeInternal(
            @Nullable ProgramTraceProcessor programTraceProcessor,
            int vmTraceOptions,
            Block block,
            BlockHeader parent,
            boolean discardInvalidTxs,
            boolean acceptInvalidTransactions) {
        boolean vmTrace = programTraceProcessor != null;
        logger.trace("Start executeInternal.");
        logger.trace("applyBlock: block: [{}] tx.list: [{}]", block.getNumber(), block.getTransactionsList().size());

        // Forks the repo, does not change "repository". It will have a completely different
        // image of the repo, where the middle caches are immediately ignored.
        // In fact, while cloning everything, it asserts that no cache elements remains.
        // (see assertNoCache())
        // Which means that you must commit changes and save them to be able to recover
        // in the next block processed.
        // Note that creating a snapshot is important when the block is executed twice
        // (e.g. once while building the block in tests/mining, and the other when trying
        // to conect the block). This is because the first execution will change the state
        // of the repository to the state post execution, so it's necessary to get it to
        // the state prior execution again.

        Repository track = repositoryLocator.startTrackingAt(parent);

        maintainPrecompiledContractStorageRoots(track, activationConfig.forBlock(block.getNumber()));

        long totalGasUsed = 0;
        Coin totalPaidFees = Coin.ZERO;
        List<TransactionReceipt> receipts = new ArrayList<>();
        List<Transaction> executedTransactions = new ArrayList<>();
        Set<DataWord> deletedAccounts = new HashSet<>();
        int threadCount = 4;
        double sequentialPart = 0.0D;
        if(sequentialPart > 0){
            threadCount += 1;
        }
        double threadPercentage = (1.00 - sequentialPart) / threadCount;
        Map<Integer, Map<Integer, Transaction>> transactionsMap = getSplitTransactionsByThread(block.getTransactionsList(), threadCount, threadPercentage);
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.BLOCK_EXECUTE);

        Metric parallelMetric = profiler.start(Profiler.PROFILING_TYPE.BLOCK_EXECUTE_PARALLEL);
        ExecutorService msgQueue = Executors.newFixedThreadPool(threadCount);
        CompletionService<List<TransactionExecutionResult>> completionService = new ExecutorCompletionService<>(msgQueue);
        for (Map.Entry<Integer, Map<Integer, Transaction>> threadSet : transactionsMap.entrySet()) {
            if (threadSet.getKey() == threadCount) {
                continue;
            }
            logger.warn("Parallel run of [{}] transactions for block: [{}] thread: [{}]", threadSet.getValue().size(), block.getNumber(), threadSet.getKey());
            TransactionConcurrentExecutor concurrentExecutor = new TransactionConcurrentExecutor(
                    threadSet.getValue(),
                    transactionExecutorFactory,
                    track,
                    block,
                    vmTrace,
                    vmTraceOptions,
                    acceptInvalidTransactions,
                    discardInvalidTxs,
                    programTraceProcessor);
            completionService.submit(concurrentExecutor);
        }

        msgQueue.shutdown();
        int received = 0;
        logger.warn("totalThreads: {}", transactionsMap.entrySet().size());
        while(received < transactionsMap.entrySet().size()) {
            try {
                Future<List<TransactionExecutionResult>> resultFuture = completionService.take();
                List<TransactionExecutionResult> results = resultFuture.get();
                received ++;
                for (TransactionExecutionResult result : results) {
                    deletedAccounts.addAll(result.getDeletedAccounts());
                    executedTransactions.add(result.getExecutedTransaction());
                    receipts.add(result.getReceipt());
                    if (this.registerProgramResults) {
                        transactionResults.put(result.getTxHash(), result.getResult());
                    }
                    totalPaidFees.add(result.getTotalPaidFees());
                    totalGasUsed += result.getTotalGasUsed();
                }
                logger.warn("Completed thread {} of {}", received, transactionsMap.entrySet().size());
            }
            catch (InterruptedException | ExecutionException e) {
                profiler.stop(parallelMetric);
                e.printStackTrace();
            } catch (TransactionException e) {
                profiler.stop(parallelMetric);
                return BlockResult.INTERRUPTED_EXECUTION_BLOCK_RESULT;
            }
        }

        if(sequentialPart > 0){
            Map<Integer, Transaction> pendingTxs = transactionsMap.get(transactionsMap.size()); // get the last sub set of transactions, those that wa
            executePendingTransactions(
                    pendingTxs,
                    block,
                    track,
                    totalGasUsed,
                    vmTrace,
                    vmTraceOptions,
                    deletedAccounts,
                    acceptInvalidTransactions,
                    discardInvalidTxs,
                    executedTransactions,
                    metric,
                    programTraceProcessor,
                    totalPaidFees,
                    receipts);
        }
        profiler.stop(parallelMetric);

        logger.trace("End txs executions.");

        if (!vmTrace) {
            logger.trace("Saving track.");
            track.save();
            logger.trace("End saving track.");
        }

        logger.trace("Building execution results.");
        BlockResult result = new BlockResult(
                block,
                executedTransactions,
                receipts,
                totalGasUsed,
                totalPaidFees,
                vmTrace ? null : track.getTrie()
        );
        profiler.stop(metric);
        logger.trace("End executeInternal.");
        return result;
    }

    private BlockResult executePendingTransactions(
            Map<Integer, Transaction> transactions,
            Block block,
            Repository track,
            long totalGasUsed,
            boolean vmTrace,
            int vmTraceOptions,
            Set<DataWord> deletedAccounts,
            boolean acceptInvalidTransactions,
            boolean discardInvalidTxs,
            List<Transaction> executedTransactions,
            Metric metric,
            ProgramTraceProcessor programTraceProcessor,
            Coin totalPaidFees,
            List<TransactionReceipt> receipts
            ) {
        logger.warn("sequentially run transactions for block: [{}] count: [{}]", block.getNumber(), transactions.size());
        for (Map.Entry<Integer, Transaction> txEntry : transactions.entrySet()) {
            Transaction tx = txEntry.getValue();
            Integer txIndex = txEntry.getKey();
            TransactionExecutor txExecutor = transactionExecutorFactory.newInstance(
                    tx,
                    txIndex,
                    block.getCoinbase(),
                    track,
                    block,
                    totalGasUsed,
                    vmTrace,
                    vmTraceOptions,
                    deletedAccounts);
            boolean transactionExecuted = txExecutor.executeTransaction();

            if (!acceptInvalidTransactions && !transactionExecuted) {
                if (discardInvalidTxs) {
                    logger.warn("block: [{}] discarded tx: [{}]", block.getNumber(), tx.getHash());
                    continue;
                } else {
                    logger.warn("block: [{}] execution interrupted because of invalid tx: [{}]",
                            block.getNumber(), tx.getHash());
                    profiler.stop(metric);
                    return BlockResult.INTERRUPTED_EXECUTION_BLOCK_RESULT;
                }
            }

            executedTransactions.add(tx);

            if (this.registerProgramResults) {
                this.transactionResults.put(tx.getHash(), txExecutor.getResult());
            }

            if (vmTrace) {
                txExecutor.extractTrace(programTraceProcessor);
            }

            logger.trace("tx executed");

            // No need to commit the changes here. track.commit();

            logger.trace("track commit");

            long gasUsed = txExecutor.getGasUsed();
            totalGasUsed += gasUsed;
            Coin paidFees = txExecutor.getPaidFees();
            if (paidFees != null) {
                totalPaidFees = totalPaidFees.add(paidFees);
            }

            deletedAccounts.addAll(txExecutor.getResult().getDeleteAccounts());

            TransactionReceipt receipt = new TransactionReceipt();
            receipt.setGasUsed(gasUsed);
            receipt.setCumulativeGas(totalGasUsed);

            receipt.setTxStatus(txExecutor.getReceipt().isSuccessful());
            receipt.setTransaction(tx);
            receipt.setLogInfoList(txExecutor.getVMLogs());
            receipt.setStatus(txExecutor.getReceipt().getStatus());

            logger.trace("block: [{}] executed tx: [{}]", block.getNumber(), tx.getHash());

            logger.trace("tx[{}].receipt", txIndex);

            receipts.add(receipt);

            logger.trace("tx done");
        }
        return null;
    }

    private Map<Integer, Map<Integer, Transaction>> getSplitTransactionsByThread(List<Transaction> transactionsList, int threadCount, double threadPercentage) {
        Map<RskAddress, Map<Integer, Transaction>> groupedTransactions = new LinkedHashMap<>();
        Map<Integer, Transaction> indexedTxs = new HashMap<>();
        Map<Integer, Map<Integer, Transaction>> result = new HashMap<>();
        int i = 1;
        for (Transaction tx: transactionsList) {
            indexedTxs.put(i, tx);
            i++;
        }
        for (Map.Entry<Integer, Transaction> txEntry :
                indexedTxs.entrySet()) {
            groupedTransactions.computeIfAbsent(txEntry.getValue().getSender(), k -> new LinkedHashMap<>()).put(txEntry.getKey(), txEntry.getValue());
        }

        int amountOfTransactionsPerConcurrentThread = (int) (transactionsList.size() * threadPercentage);
        logger.warn("amountOfTransactionsPerConcurrentThread: [{}]", amountOfTransactionsPerConcurrentThread);
        int currentTransactionIndex = 0;
        int currentThread = 1;
        for (RskAddress address : groupedTransactions.keySet()) {
            // if the current index is higher than the expected index for the current thread
            // and if the current thread is not the last one
            // move to the next thread
            if(amountOfTransactionsPerConcurrentThread * currentThread < currentTransactionIndex && currentThread < threadCount){
                currentThread++;
            }
            Map<Integer, Transaction> senderTxs = groupedTransactions.get(address);
            // add all the transactions belonging to that address, in order. It assumes transactions are already ordered (nonce).
            result.computeIfAbsent(currentThread, k -> new LinkedHashMap<>()).putAll(senderTxs);
            logger.warn("sender: {}, txs: {}", address.toString(), senderTxs.size());
            currentTransactionIndex += senderTxs.size();
        }
        return result;
    }

    /**
     * Precompiled contracts storage is setup like any other contract for consistency. Here, we apply this logic on the
     * exact activation block.
     * This method is called automatically for every block except for the Genesis (which makes an explicit call).
     */
    public static void maintainPrecompiledContractStorageRoots(Repository track, ActivationConfig.ForBlock activations) {
        if (activations.isActivating(RSKIP126)) {
            for (RskAddress addr : PrecompiledContracts.GENESIS_ADDRESSES) {
                if (!track.isExist(addr)) {
                    track.createAccount(addr);
                }
                track.setupContract(addr);
            }
        }

        for (Map.Entry<RskAddress, ConsensusRule> e : PrecompiledContracts.CONSENSUS_ENABLED_ADDRESSES.entrySet()) {
            ConsensusRule contractActivationRule = e.getValue();
            if (activations.isActivating(contractActivationRule)) {
                RskAddress addr = e.getKey();
                track.createAccount(addr);
                track.setupContract(addr);
            }
        }
    }

    @VisibleForTesting
    public static byte[] calculateLogsBloom(List<TransactionReceipt> receipts) {
        Bloom logBloom = new Bloom();

        for (TransactionReceipt receipt : receipts) {
            logBloom.or(receipt.getBloomFilter());
        }

        return logBloom.getData();
    }

    public ProgramResult getProgramResult(Keccak256 txhash) {
        return this.transactionResults.get(txhash);
    }

    public void setRegisterProgramResults(boolean value) {
        this.registerProgramResults = value;
        this.transactionResults.clear();
    }
}
