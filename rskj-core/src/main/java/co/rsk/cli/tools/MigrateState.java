package co.rsk.cli.tools;

import co.rsk.trie.TrieStore;
import co.rsk.trie.TrieStoreImpl;
import org.ethereum.datasource.DbKind;
import org.ethereum.datasource.KeyValueDataSource;
import org.ethereum.datasource.KeyValueDataSourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import javax.annotation.Nonnull;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The entry point for export state CLI tool
 * This is an experimental/unsupported tool
 *
 * This tool can be interrupted and re-started and it will continue the migration from
 * the point where it left.
 *
 * Required cli args:
 *
 * - args[0] - command "migrate" or "check" or "copy" or "showroot"
 *
 * MIGRATE: (migrate does not writes key or children if dst key already exists)
 * - args[1] - root
 * - args[2] - src file path
 * - args[3] - src database format
 * - args[4] - dst file path
 * - args[5] - dst database format
 *
 * COPY: (copy a tree or all key/values obtained by keys() property)
 * - args[1] - root (hex) or "all" to copy all key/values
 * - args[2] - src file path
 * - args[3] - src database format
 * - args[4] - dst file path
 * - args[5] - dst database format
 *
 * CHECK: (checks that a certain tree in the db is good, by scannign recursively)
 * - args[1] - root (hex)
 * - args[2] - file path
 * - args[3] - database format
 *
 * FIX (fixes missing key/values on a database (dst) by retrieving the missing values from another (src)
 * - args[1] - root (hex)
 * - args[2] - src file path (this is the one that is fine)
 * - args[3] - src database format
 * - args[4] - dst file path
 * - args[5] - dst database format
 *
 * MIGRATE2 (migrates a tree from a database src, into another database (dst)
 * using a third database (cache) as cache)
 * Reads will be first performed on cache, and if not found, on src.
 *
 * NODEEXISTS
 * - args[1] - key (hex)
 * - args[2] - file path
 * - args[3] - database format
 *
 * VALUEEXISTS
 * - args[1] - key (hex)
 * - args[2] - file path
 * - args[3] - database format
 *
 * For maximum performance, disable the state cache by adding the argument:
 * -Xcache.states.max-elements=0
 */
public class MigrateState {
    private static final Logger logger = LoggerFactory.getLogger(MigrateState.class);

    private static final int COMMAND_IDX = 0;
    private static final int ROOT_IDX = 1;
    private static final int SRC_FILE_PATH_IDX = 2;
    private static final int SRC_FILE_FORMAT_IDX = 3;
    private static final int DST_PATH_IDX = 4;
    private static final int DST_FILE_FORMAT_IDX = 5;
    private static final int CACHE_PATH_IDX = 6;
    private static final int CACHE_FILE_FORMAT_IDX = 7;

    public static void main(String[] args) {
        new MigrateState().onExecute(args);
    }

    protected void onExecute(@Nonnull String[] args) {
        MigrateStateUtil.Command command = MigrateStateUtil.Command.ofName(args[COMMAND_IDX].toUpperCase(Locale.ROOT));

        TrieStore srcTrieStore = null;
        KeyValueDataSource dsDst = null;

        String srcFilePath = args[SRC_FILE_PATH_IDX];
        boolean readOnlySrc = (command == MigrateStateUtil.Command.CHECK) ||
                (command == MigrateStateUtil.Command.NODEEXISTS) ||
                (command == MigrateStateUtil.Command.VALUEEXISTS);

        KeyValueDataSource dsSrc;
        DbKind srcFileFmt = DbKind.ofName(args[SRC_FILE_FORMAT_IDX]);

        dsSrc = KeyValueDataSourceUtils.makeDataSource(Paths.get(srcFilePath), srcFileFmt);

        logger.info("src path: " + srcFilePath);
        logger.info("src format: " + srcFileFmt);

        if (!readOnlySrc) {
            // Use two databases

            String dstFilePath = args[DST_PATH_IDX];
            DbKind dstFileFmt = DbKind.ofName(args[DST_FILE_FORMAT_IDX]);
            dsDst = KeyValueDataSourceUtils.makeDataSource(Paths.get(dstFilePath), dstFileFmt);
            logger.info("dst path: " + dstFilePath);
            logger.info("dst format: " + dstFileFmt);
        }

        byte[] root = null;
        String cacheFilePath;
        DbKind cacheFileFmt;
        KeyValueDataSource dsCache = null;

        if ((command == MigrateStateUtil.Command.NODEEXISTS)
                || (command == MigrateStateUtil.Command.VALUEEXISTS)) {
            logger.info("check key existence...");
            root = Hex.decode(args[ROOT_IDX]);
            logger.info("State key: " + Hex.toHexString(root));
            // do not migrate: check that migration is ok.
            srcTrieStore = new TrieStoreImpl(dsSrc);
        } else if (command == MigrateStateUtil.Command.CHECK) {
            logger.info("checking...");
            root = Hex.decode(args[ROOT_IDX]);
            logger.info("State root: " + Hex.toHexString(root));
            // do not migrate: check that migration is ok.
            srcTrieStore = new TrieStoreImpl(dsSrc);
        } else if (command == MigrateStateUtil.Command.FIX) {
            logger.info("fixing...");
            root = Hex.decode(args[ROOT_IDX]);
            logger.info("State root: " + Hex.toHexString(root));
            // do not migrate: check that migration is ok.
            // We iterate the trie over the new (dst) database, to make it faster
            srcTrieStore = new TrieStoreImpl(dsSrc);
        } else if (command == MigrateStateUtil.Command.COPY) {
            String rootStr = args[ROOT_IDX];
            if (rootStr.equalsIgnoreCase("ALL")) {
                command = MigrateStateUtil.Command.COPYALL;
                logger.info("copying all...");
            } else {
                root = Hex.decode(args[ROOT_IDX]);
                logger.info("copying from root...");
                logger.info("State root: " + Hex.toHexString(root));
                srcTrieStore = new TrieStoreImpl(dsSrc);
            }

        } else if (command == MigrateStateUtil.Command.MIGRATE) {
            logger.info("migrating...");
            root = Hex.decode(args[ROOT_IDX]);
            srcTrieStore = new TrieStoreImpl(dsSrc);
        } else if (command == MigrateStateUtil.Command.MIGRATE2) {
            logger.info("migrating with cache...");
            cacheFilePath = args[CACHE_PATH_IDX];
            cacheFileFmt = DbKind.ofName(args[CACHE_FILE_FORMAT_IDX]);
            dsCache = KeyValueDataSourceUtils.makeDataSource(Paths.get(cacheFilePath), cacheFileFmt);
            logger.info("cache path: " + cacheFilePath);
            logger.info("cache format: " + cacheFileFmt);
            root = Hex.decode(args[ROOT_IDX]);
            srcTrieStore = new TrieStoreImpl(dsSrc);

        } else {
            System.exit(1);
        }

        MigrateStateUtil mu = new MigrateStateUtil(root, srcTrieStore, dsSrc, dsDst, dsCache);
        boolean result = mu.executeCommand(command);
        dsSrc.close();

        if ((dsDst != null) && (dsDst != dsSrc)) {
            dsDst.close();
        }

        if (!result) {
            throw new RuntimeException("The result of your operation is not correct.");
        }
    }


}
