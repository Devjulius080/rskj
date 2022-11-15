package co.rsk.peg;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.rsk.bitcoinj.core.Address;
import co.rsk.bitcoinj.core.BtcECKey;
import co.rsk.bitcoinj.core.BtcTransaction;
import co.rsk.bitcoinj.core.Coin;
import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.ScriptException;
import co.rsk.bitcoinj.core.Sha256Hash;
import co.rsk.bitcoinj.core.Utils;
import co.rsk.bitcoinj.core.VerificationException;
import co.rsk.bitcoinj.crypto.TransactionSignature;
import co.rsk.bitcoinj.script.ErpFederationRedeemScriptParser;
import co.rsk.bitcoinj.script.Script;
import co.rsk.bitcoinj.script.ScriptBuilder;
import co.rsk.bitcoinj.script.ScriptOpCodes;
import co.rsk.config.BridgeConstants;
import co.rsk.config.BridgeMainNetConstants;
import co.rsk.config.BridgeTestNetConstants;
import co.rsk.peg.resources.TestConstants;
import java.math.BigInteger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.util.encoders.Hex;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.crypto.ECKey;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class ErpFederationTest {
    private ErpFederation federation;
    private List<BtcECKey> defaultKeys;
    private List<BtcECKey> emergencyKeys;
    private long activationDelayValue;
    private ActivationConfig.ForBlock activations;

    @Before
    public void setup() {
        BridgeConstants bridgeConstants = BridgeMainNetConstants.getInstance();

        defaultKeys = bridgeConstants.getGenesisFederation().getBtcPublicKeys();
        emergencyKeys = bridgeConstants.getErpFedPubKeysList();
        activationDelayValue = bridgeConstants.getErpFedActivationDelay();

        activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP284)).thenReturn(true);

        federation = createDefaultErpFederation();
    }

    @Test
    public void getErpPubKeys() {
        Assert.assertEquals(emergencyKeys, federation.getErpPubKeys());
    }

    @Test
    public void getActivationDelay() {
        Assert.assertEquals(activationDelayValue, federation.getActivationDelay());
    }

    @Test
    public void getRedeemScript_before_RSKIP293() {
        Script redeemScript = federation.getRedeemScript();
        validateErpRedeemScript(
            redeemScript,
            activationDelayValue,
            false
        );
    }

    @Test
    public void getRedeemScript_after_RSKIP293() {
        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(true);
        federation = createDefaultErpFederation();
        Script redeemScript = federation.getRedeemScript();

        validateErpRedeemScript(
            redeemScript,
            activationDelayValue,
            true
        );
    }

    @Test
    public void getRedeemScript_changes_after_RSKIP293() {
        Script preRskip293RedeemScript = federation.getRedeemScript();

        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(true);
        federation = createDefaultErpFederation();
        Script postRskip293RedeemScript = federation.getRedeemScript();

        Assert.assertNotEquals(preRskip293RedeemScript, postRskip293RedeemScript);
    }

    @Test
    public void getP2SHScript() {
        Script p2shs = federation.getP2SHScript();
        String expectedProgram = "a914e16874869871d2fbc116ed503699b42b886e4eeb87";

        Assert.assertEquals(expectedProgram, Hex.toHexString(p2shs.getProgram()));
        Assert.assertEquals(3, p2shs.getChunks().size());
        Assert.assertEquals(
            federation.getAddress(),
            p2shs.getToAddress(NetworkParameters.fromID(NetworkParameters.ID_REGTEST))
        );
    }

    @Test
    public void getAddress() {
        String fedAddress = federation.getAddress().toBase58();
        String expectedAddress = "2NDo5A7r5GbgMt9hH4iQ3fvuxspixP67xgU";

        Assert.assertEquals(expectedAddress, fedAddress);
    }

    @Test
    public void getErpPubKeys_compressed_public_keys() {
        Assert.assertEquals(emergencyKeys, federation.getErpPubKeys());
    }

    @Test
    public void getErpPubKeys_uncompressed_public_keys() {
        // Public keys used for creating federation, but uncompressed format now
        List<BtcECKey> uncompressedErpKeys = emergencyKeys
            .stream()
            .map(BtcECKey::decompress)
            .collect(Collectors.toList());

        // Recreate federation
        ErpFederation federationWithUncompressedKeys = new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST),
            uncompressedErpKeys,
            activationDelayValue,
            mock(ActivationConfig.ForBlock.class)
        );

        Assert.assertEquals(emergencyKeys, federationWithUncompressedKeys.getErpPubKeys());
    }

    @Test
    public void getErpRedeemScript_compareOtherImplementation() throws IOException {
        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(true);

        byte[] rawRedeemScripts;
        try {
            rawRedeemScripts = Files.readAllBytes(Paths.get("src/test/resources/redeemScripts.json"));
        } catch (IOException e) {
            System.out.println("redeemScripts.json file not found");
            throw(e);
        }

        RawGeneratedRedeemScript[] generatedScripts = new ObjectMapper().readValue(rawRedeemScripts, RawGeneratedRedeemScript[].class);
        for (RawGeneratedRedeemScript generatedScript : generatedScripts) {
            Federation erpFederation = new ErpFederation(
                FederationTestUtils.getFederationMembersWithBtcKeys(generatedScript.mainFed),
                ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
                0L,
                NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
                generatedScript.emergencyFed,
                generatedScript.timelock,
                activations
            );

            Script rskjScript = erpFederation.getRedeemScript();
            Script alternativeScript = generatedScript.script;
            Assert.assertEquals(alternativeScript, rskjScript);
        }
    }

    @Test
    public void createErpFederation_testnet_constants_before_RSKIP293() {
        createErpFederation(BridgeTestNetConstants.getInstance(), false);
    }

    @Test
    public void createErpFederation_testnet_constants_after_RSKIP293() {
        createErpFederation(BridgeTestNetConstants.getInstance(), true);
    }

    @Test
    public void createErpFederation_mainnet_constants_before_RSKIP293() {
        createErpFederation(BridgeMainNetConstants.getInstance(), false);
    }

    @Test
    public void createErpFederation_mainnet_constants_after_RSKIP293() {
        createErpFederation(BridgeMainNetConstants.getInstance(), true);
    }

    @Test(expected = VerificationException.class)
    public void createInvalidErpFederation_negativeCsvValue() {
        new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST),
            emergencyKeys,
            -100L,
            activations
        );
    }

    @Test(expected = VerificationException.class)
    public void createInvalidErpFederation_csvValueNegative() {
        new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST),
            emergencyKeys,
            -100,
            activations
        );
    }

    @Test(expected = VerificationException.class)
    public void createInvalidErpFederation_csvValueZero() {
        new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST),
            emergencyKeys,
            0,
            activations
        );
    }

    @Test(expected = VerificationException.class)
    public void createInvalidErpFederation_csvValueAboveMax() {
        new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST),
            emergencyKeys,
            ErpFederationRedeemScriptParser.MAX_CSV_VALUE + 1,
            activations
        );
    }

    @Test
    public void getRedeemScript_before_RSKIP_284_testnet() {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP284)).thenReturn(false);

        Federation erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyKeys,
            activationDelayValue,
            activations
        );

        Assert.assertEquals(TestConstants.ERP_TESTNET_REDEEM_SCRIPT, erpFederation.getRedeemScript());
    }

    @Test
    public void getRedeemScript_before_RSKIP_284_mainnet() {
        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP284)).thenReturn(false);

        Federation erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_MAINNET),
            emergencyKeys,
            activationDelayValue,
            activations
        );

        Assert.assertNotEquals(TestConstants.ERP_TESTNET_REDEEM_SCRIPT, erpFederation.getRedeemScript());
        validateErpRedeemScript(
            erpFederation.getRedeemScript(),
            activationDelayValue,
            false
        );
    }

    @Test
    public void getRedeemScript_after_RSKIP_284_testnet() {
        Federation erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyKeys,
            activationDelayValue,
            activations
        );

        Assert.assertNotEquals(TestConstants.ERP_TESTNET_REDEEM_SCRIPT, erpFederation.getRedeemScript());
        validateErpRedeemScript(
            erpFederation.getRedeemScript(),
            activationDelayValue,
            false
        );
    }

    @Test
    public void getRedeemScript_after_RSKIP_284_mainnet() {
        Federation erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_MAINNET),
            emergencyKeys,
            activationDelayValue,
            activations
        );

        Assert.assertNotEquals(TestConstants.ERP_TESTNET_REDEEM_SCRIPT, erpFederation.getRedeemScript());
        validateErpRedeemScript(
            erpFederation.getRedeemScript(),
            activationDelayValue,
            false
        );
    }

    @Test
    public void testEquals_basic() {
        Assert.assertEquals(federation, federation);

        Assert.assertNotEquals(null, federation);
        Assert.assertNotEquals(federation, new Object());
        Assert.assertNotEquals("something else", federation);
    }

    @Test
    public void testEquals_same() {
        Federation otherFederation = new ErpFederation(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams(),
            federation.getErpPubKeys(),
            federation.getActivationDelay(),
            activations
        );

        Assert.assertEquals(federation, otherFederation);
    }

    @Test
    public void testEquals_differentNumberOfMembers() {
        Federation otherFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300, 400, 500, 600, 700),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams(),
            federation.getErpPubKeys(),
            federation.getActivationDelay(),
            activations
        );

        Assert.assertNotEquals(federation, otherFederation);
    }

    @Test
    public void testEquals_differentCreationTime() {
        Federation otherFederation = new ErpFederation(
            federation.getMembers(),
            Instant.now(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams(),
            federation.getErpPubKeys(),
            federation.getActivationDelay(),
            activations
        );

        Assert.assertNotEquals(federation, otherFederation);
    }

    @Test
    public void testEquals_differentCreationBlockNumber() {
        Federation otherFederation = new ErpFederation(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber() + 1,
            federation.getBtcParams(),
            federation.getErpPubKeys(),
            federation.getActivationDelay(),
            activations
        );

        Assert.assertNotEquals(federation, otherFederation);
    }

    @Test
    public void testEquals_differentNetworkParameters() {
        Federation otherFederation = new ErpFederation(
            federation.getMembers(),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            NetworkParameters.fromID(NetworkParameters.ID_MAINNET),
            federation.getErpPubKeys(),
            federation.getActivationDelay(),
            activations
        );

        Assert.assertNotEquals(federation, otherFederation);
    }

    @Test
    public void testEquals_differentMembers() {
        Federation otherFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(101, 201, 301),
            federation.getCreationTime(),
            federation.getCreationBlockNumber(),
            federation.getBtcParams(),
            federation.getErpPubKeys(),
            federation.getActivationDelay(),
            activations
        );

        Assert.assertNotEquals(federation, otherFederation);
    }

    @Test
    public void testEquals_differentRedeemScript() {
        ActivationConfig.ForBlock activationsPre = mock(ActivationConfig.ForBlock.class);
        when(activationsPre.isActive(ConsensusRule.RSKIP284)).thenReturn(false);

        ActivationConfig.ForBlock activationsPost = mock(ActivationConfig.ForBlock.class);
        when(activationsPost.isActive(ConsensusRule.RSKIP284)).thenReturn(true);

        // Both federations created before RSKIP284 with the same data, should have the same redeem script
        Federation erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyKeys,
            activationDelayValue,
            activationsPre
        );

        Federation otherErpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyKeys,
            activationDelayValue,
            activationsPre
        );

        Assert.assertEquals(erpFederation, otherErpFederation);

        // One federation created after RSKIP284 with the same data, should have different redeem script
        otherErpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyKeys,
            activationDelayValue,
            activationsPost
        );

        Assert.assertNotEquals(erpFederation, otherErpFederation);

        // The other federation created after RSKIP284 with the same data, should have same redeem script
        erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersFromPks(100, 200, 300),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyKeys,
            activationDelayValue,
            activationsPost
        );

        Assert.assertEquals(erpFederation, otherErpFederation);
    }

    @Ignore("Can't recreate the hardcoded redeem script since the needed CSV value is above the max. Keeping the test ignored as testimonial")
    @Test(expected = FederationCreationException.class)
    public void createErpFedWithSameRedeemScriptAsHardcodedOne_after_RSKIP293_fails() {
        // We can't test the same condition before RSKIP293 since the serialization used by bj-thin
        // prior to RSKIP293 enforces the CSV value to be encoded using 2 bytes.
        // The hardcoded script has a 3 byte long CSV value
        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(true);

        List<BtcECKey> standardMultisigKeys = Arrays.stream(new String[]{
            "0208f40073a9e43b3e9103acec79767a6de9b0409749884e989960fee578012fce",
            "0225e892391625854128c5c4ea4340de0c2a70570f33db53426fc9c746597a03f4",
            "02afc230c2d355b1a577682b07bc2646041b5d0177af0f98395a46018da699b6da",
            "0344a3c38cd59afcba3edcebe143e025574594b001700dec41e59409bdbd0f2a09",
            "039a060badbeb24bee49eb2063f616c0f0f0765d4ca646b20a88ce828f259fcdb9"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).sorted(BtcECKey.PUBKEY_COMPARATOR).collect(Collectors.toList());

        List<BtcECKey> emergencyMultisigKeys = Arrays.stream(new String[]{
            "0216c23b2ea8e4f11c3f9e22711addb1d16a93964796913830856b568cc3ea21d3",
            "0275562901dd8faae20de0a4166362a4f82188db77dbed4ca887422ea1ec185f14",
            "034db69f2112f4fb1bb6141bf6e2bd6631f0484d0bd95b16767902c9fe219d4a6f"
        }).map(hex -> BtcECKey.fromPublicOnly(Hex.decode(hex))).sorted(BtcECKey.PUBKEY_COMPARATOR).collect(Collectors.toList());

        long activationDelay = 5_295_360L;

        new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(standardMultisigKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_TESTNET),
            emergencyMultisigKeys,
            activationDelay,
            activations
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_before_RSKIP293_testnet_using_erp_multisig_can_spend() {
        BridgeConstants constants = BridgeTestNetConstants.getInstance();

        // The CSV value defined in BridgeTestnetConstants,
        // actually allows the emergency multisig to spend before the expected amount of blocks
        // Since it's encoded as BE and decoded as LE, the result is a number lower than the one defined in the constant
        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            false,
            true
        );
    }

    @Test(expected = ScriptException.class)
    public void spendFromErpFed_before_RSKIP293_testnet_using_erp_multisig_cant_spend() {
        BridgeConstants constants = BridgeTestNetConstants.getInstance();

        // Should fail due to the wrong encoding of the CSV value
        // In this case, the value 300 when encoded as BE and decoded as LE results in a larger number
        // This causes the validation to fail
        spendFromErpFed(
            constants.getBtcParams(),
            300,
            false,
            true
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_before_RSKIP293_testnet_using_standard_multisig() {
        BridgeConstants constants = BridgeTestNetConstants.getInstance();

        // Should validate since it's not executing the path of the script with the CSV value
        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            false,
            false
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_before_RSKIP293_mainnet_using_erp_multisig_can_spend() {
        BridgeConstants constants = BridgeMainNetConstants.getInstance();

        // The CSV value defined in BridgeMainnetConstants,
        // actually allows the emergency multisig to spend before the expected amount of blocks
        // Since it's encoded as BE and decoded as LE, the result is a number lower than the one defined in the constant
        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            false,
            true
        );
    }

    @Test(expected = ScriptException.class)
    public void spendFromErpFed_before_RSKIP293_mainnet_using_erp_multisig_cant_spend() {
        BridgeConstants constants = BridgeMainNetConstants.getInstance();

        // Should fail due to the wrong encoding of the CSV value
        // In this case, the value 300 when encoded as BE and decoded as LE results in a larger number
        // This causes the validation to fail
        spendFromErpFed(
            constants.getBtcParams(),
            300,
            false,
            true
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_before_RSKIP293_mainnet_using_standard_multisig() {
        BridgeConstants constants = BridgeMainNetConstants.getInstance();

        // Should validate since it's not executing the path of the script with the CSV value
        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            false,
            false
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_after_RSKIP293_testnet_using_erp_multisig() {
        BridgeConstants constants = BridgeTestNetConstants.getInstance();

        // Post RSKIP293 activation it should encode the CSV value correctly
        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            true,
            true
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_after_RSKIP293_testnet_using_standard_multisig() {
        BridgeConstants constants = BridgeTestNetConstants.getInstance();

        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            true,
            false
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_after_RSKIP293_mainnet_using_erp_multisig() {
        BridgeConstants constants = BridgeMainNetConstants.getInstance();

        // Post RSKIP293 activation it should encode the CSV value correctly
        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            true,
            true
        );
    }

    @Test(expected = Test.None.class)
    public void spendFromErpFed_after_RSKIP293_mainnet_using_standard_multisig() {
        BridgeConstants constants = BridgeMainNetConstants.getInstance();

        spendFromErpFed(
            constants.getBtcParams(),
            constants.getErpFedActivationDelay(),
            true,
            false
        );
    }

    private void spendFromErpFed(
        NetworkParameters networkParameters,
        long activationDelay,
        boolean isRskip293Active,
        boolean signWithEmergencyMultisig) {

        // Below code can be used to create a transaction spending from the emergency multisig in testnet or mainnet
        String FUND_TX_HASH = "678a562c8ed326e7e422248abeacb150597f6f3dd4470ae7d555158f8c4c50e4";
        final Coin FUND_TX_VALUE = Coin.valueOf(10_000L);
        final Coin TX_FEE = Coin.valueOf(1_000L);
        final int OUTPUT_INDEX = 1; // Remember to change this value accordingly in case of using an existing raw tx
        final Address DESTINATION_ADDRESS = Address.fromBase58(networkParameters, "mgagqa2QuxFfLQXLRyWe5TorLMbP9Maiud");

        byte[] publicKeyBytes;
        BtcECKey btcKey;
        ECKey rskKey;

        // Created with GenNodeKeyId using seed 'fed2', used for fed3 to keep keys sorted
        publicKeyBytes = Hex.decode("020e67a1daba745be62206fa944bacc02a9b87c017b0a0d672ddb349fe838450dd");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed1 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed1PrivKey = BtcECKey.fromPrivate(Hex.decode("7147b50a8a30710a6ce66d144a9e30f9092e14ab0199b0180421a5146d635983"));

        publicKeyBytes = Hex.decode("021549fff7ab02f06c8ee6375b428abeef45ab553a5837f42db6f2e5c5a19ca495");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed2 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed2PrivKey = BtcECKey.fromPrivate(Hex.decode("43b3465f4e11beeaffb66552f1cc1be2ac41feded77ee08c147646b60a177442"));

        publicKeyBytes = Hex.decode("02196385ffd175d0d129aa25574a2449d1deec93c88dfc3ebbcdc01db06d4dd00d");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed3 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed3PrivKey = BtcECKey.fromPrivate(Hex.decode("929dc7eee8ef3f66c6c756bcd0eda3e1319539d0c51f9d61f694fa347b936591"));

        // Created with GenNodeKeyId using seed 'fed3', used for fed2 to keep keys sorted
        publicKeyBytes = Hex.decode("0243a0ab0169f577de86ec47ad4d2679ab1d41316ada2f97e0b8d2bd3e17f93d04");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed4 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed4PrivKey = BtcECKey.fromPrivate(Hex.decode("d337775d4e8dc89de838f504bdeccb91754f09e696a5723ed0ef245d78ae5acf"));

        // Created with GenNodeKeyId using seed 'fed2', used for fed3 to keep keys sorted
        publicKeyBytes = Hex.decode("02910dc283b2d30e055d44f9b1bf4bb8ae6fb6da4b99770b2a805388e7d4561f2e");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed5 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed5PrivKey = BtcECKey.fromPrivate(Hex.decode("7ff9baa065dad4b43ec9fdeeef2072f4d0e73c38deffb66665e8a25f97f981b8"));

        // Created with GenNodeKeyId using seed 'fed1'
        publicKeyBytes = Hex.decode("02e81d94dc61728d5eb53133b39e04ca72fa68b8da71fd8c15b0ef2c20e39b08c6");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed6 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed6PrivKey = BtcECKey.fromPrivate(Hex.decode("b264a70af886d184785aa0782ab84bf7ad582ce506915099023437616dbdba53"));

        // Created with GenNodeKeyId using seed 'fed2', used for fed3 to keep keys sorted
        publicKeyBytes = Hex.decode("03ddec21ecc90d3611cdef846a970e1fc7a14c132013f3bfcc4368f2e4be22329d");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed7 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed7PrivKey = BtcECKey.fromPrivate(Hex.decode("fa013890aa14dd269a0ca16003cabde1688021358b662d17b1e8c555f5cccc6e"));

        // Created with GenNodeKeyId using seed 'fed2', used for fed3 to keep keys sorted
        publicKeyBytes = Hex.decode("03ba98e552f5b5c80b7e1851c78fdda36f86661d38237915aede5a1427c315140c");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed8 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed8PrivKey = BtcECKey.fromPrivate(Hex.decode("fa013890aa14dd269a0ca16003cabde1688021358b662d17b1e8c555f5cccc6e"));

        // Created with GenNodeKeyId using seed 'fed2', used for fed3 to keep keys sorted
        publicKeyBytes = Hex.decode("0344249c412ffdb5f42131527040d5879803a44b4968eea9a5244b4d044945829c");
        btcKey = BtcECKey.fromPublicOnly(publicKeyBytes);
        rskKey = ECKey.fromPublicOnly(publicKeyBytes);
        FederationMember fed9 = new FederationMember(btcKey, rskKey, rskKey);
        BtcECKey fed9PrivKey = BtcECKey.fromPrivate(Hex.decode("fa013890aa14dd269a0ca16003cabde1688021358b662d17b1e8c555f5cccc6e"));

        // Created with GenNodeKeyId using seed 'erp1'
        publicKeyBytes = Hex.decode("048f5a88b08d75765b36951254e68060759de5be7e559972c37c67fc8cedafeb2643a4a8a618125530e275fe310c72dbdd55fa662cdcf8e134012f8a8d4b7e8400");
        BtcECKey erp1Key = BtcECKey.fromPublicOnly(publicKeyBytes);
        BtcECKey erp1PrivKey = BtcECKey.fromPrivate(Hex.decode("1f28656deb5f108f8cdf14af34ac4ff7a5643a7ac3f77b8de826b9ad9775f0ca"));

        // Created with GenNodeKeyId using seed 'erp2'
        publicKeyBytes = Hex.decode("04deba35a96add157b6de58f48bb6e23bcb0a17037bed1beb8ba98de6b0a0d71d60f3ce246954b78243b41337cf8f93b38563c3bcd6a5329f1d68c057d0e5146e8");
        BtcECKey erp2Key = BtcECKey.fromPublicOnly(publicKeyBytes);
        BtcECKey erp2PrivKey = BtcECKey.fromPrivate(Hex.decode("4e58ebe9cd04ffea5ab81dd2aded3ab8a63e44f3b47aef334e369d895c351646"));

        // Created with GenNodeKeyId using seed 'erp3'
        publicKeyBytes = Hex.decode("04c34fcd05cef2733ea7337c37f50ae26245646aba124948c6ff8dcdf82128499808fc9148dfbc0e0ab510b4f4a78bf7a58f8b6574e03dae002533c5059973b61f");
        BtcECKey erp3Key = BtcECKey.fromPublicOnly(publicKeyBytes);
        BtcECKey erp3PrivKey = BtcECKey.fromPrivate(Hex.decode("57e8d2cd51c3b076ca96a1043c8c6d32c6c18447e411a6279cda29d70650977b"));

        publicKeyBytes = Hex.decode("04196385ffd175d0d129aa25574a2449d1deec93c88dfc3ebbcdc01db06d4dd00d4bb9979937f0d9a83c43d1017d3f30fa89f0c6414e1f3bbda87ad3a52a8c4da2");
        BtcECKey erp4Key = BtcECKey.fromPublicOnly(publicKeyBytes);
        BtcECKey erp4PrivKey = BtcECKey.fromPrivate(Hex.decode("929dc7eee8ef3f66c6c756bcd0eda3e1319539d0c51f9d61f694fa347b936591"));

        ActivationConfig.ForBlock activations = mock(ActivationConfig.ForBlock.class);
        when(activations.isActive(ConsensusRule.RSKIP284)).thenReturn(true);
        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(isRskip293Active);
        ErpFederation erpFed = new ErpFederation(
            Arrays.asList(fed1, fed2, fed3, fed4, fed5, fed6, fed7, fed8, fed9),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            networkParameters,
            Arrays.asList(erp4Key, erp1Key, erp2Key, erp3Key),
            activationDelay,
            activations
        );
        System.out.println("Erp federation address: " + erpFed.getAddress()); // 2N5s85F4YsUyhDfBaj3n3mcC41M5ZGoyaeA

        if (FUND_TX_HASH.isEmpty()) {
            BtcTransaction pegInTx = new BtcTransaction(networkParameters);
            pegInTx.addOutput(FUND_TX_VALUE, erpFed.getAddress());

            FUND_TX_HASH = pegInTx.getHashAsString();
        }

        BtcTransaction pegOutTx = new BtcTransaction(networkParameters);
        pegOutTx.addInput(Sha256Hash.wrap(Hex.decode(FUND_TX_HASH)), OUTPUT_INDEX, new Script(new byte[]{}));
        pegOutTx.addOutput(FUND_TX_VALUE.minus(TX_FEE), DESTINATION_ADDRESS);
        pegOutTx.setVersion(2);

        if (signWithEmergencyMultisig) {
            pegOutTx.getInput(0).setSequenceNumber(activationDelay);
        }

        // Create signatures
        Sha256Hash sigHash = pegOutTx.hashForSignature(
            0,
            erpFed.getRedeemScript(),
            BtcTransaction.SigHash.ALL,
            false
        );

        BtcECKey.ECDSASignature signature1;
        BtcECKey.ECDSASignature signature2;
        BtcECKey.ECDSASignature signature3;
        BtcECKey.ECDSASignature signature4;
        BtcECKey.ECDSASignature signature5;

            signature1 = fed1PrivKey.sign(sigHash);
            signature2 = fed2PrivKey.sign(sigHash);
            signature3 = fed3PrivKey.sign(sigHash);
            signature4 = fed4PrivKey.sign(sigHash);
            signature5 = fed5PrivKey.sign(sigHash);


        // Try different signature permutations
        Script inputScript = createInputScript(
            erpFed.getRedeemScript(),
            Arrays.asList(signature1, signature2, signature3, signature4, signature5),
            signWithEmergencyMultisig
        );
        pegOutTx.getInput(0).setScriptSig(inputScript);
        inputScript.correctlySpends(pegOutTx,0, erpFed.getP2SHScript());

        // Uncomment to print the raw tx in console and broadcast https://blockstream.info/testnet/tx/push
        System.out.println(Hex.toHexString(pegOutTx.bitcoinSerialize()));
    }

    private void createErpFederation(BridgeConstants constants, boolean isRskip293Active) {
        when(activations.isActive(ConsensusRule.RSKIP293)).thenReturn(isRskip293Active);

        Federation erpFederation = new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            constants.getBtcParams(),
            constants.getErpFedPubKeysList(),
            constants.getErpFedActivationDelay(),
            activations
        );

        validateErpRedeemScript(
            erpFederation.getRedeemScript(),
            defaultKeys,
            constants.getErpFedPubKeysList(),
            constants.getErpFedActivationDelay(),
            isRskip293Active
        );
    }

    private Script createInputScript(
        Script fedRedeemScript,
        List<BtcECKey.ECDSASignature> signatures,
        boolean signWithTheEmergencyMultisig) {

        List<TransactionSignature> txSignatures = signatures.stream().map(sig -> new TransactionSignature(
            sig,
            BtcTransaction.SigHash.ALL,
            false
        )).collect(Collectors.toList());

        int flowOpCode = signWithTheEmergencyMultisig ? 1 : 0;
        ScriptBuilder scriptBuilder = new ScriptBuilder();

        scriptBuilder = scriptBuilder.number(0);
        for (TransactionSignature txSig : txSignatures) {
            scriptBuilder = scriptBuilder.data(txSig.encodeToBitcoin());
        }

        return scriptBuilder
            .number(flowOpCode)
            .data(fedRedeemScript.getProgram())
            .build();
    }

    private ErpFederation createDefaultErpFederation() {
        return new ErpFederation(
            FederationTestUtils.getFederationMembersWithBtcKeys(defaultKeys),
            ZonedDateTime.parse("2017-06-10T02:30:00Z").toInstant(),
            0L,
            NetworkParameters.fromID(NetworkParameters.ID_REGTEST),
            emergencyKeys,
            activationDelayValue,
            activations
        );
    }

    private void validateErpRedeemScript(
        Script erpRedeemScript,
        Long csvValue,
        boolean isRskip293Active) {

        validateErpRedeemScript(
            erpRedeemScript,
            defaultKeys,
            emergencyKeys,
            csvValue,
            isRskip293Active
        );
    }

    private void validateErpRedeemScript(
        Script erpRedeemScript,
        List<BtcECKey> defaultMultisigKeys,
        List<BtcECKey> emergencyMultisigKeys,
        Long csvValue,
        boolean isRskip293Active) {

        // Keys are sorted when added to the redeem script, so we need them sorted in order to validate
        defaultMultisigKeys.sort(BtcECKey.PUBKEY_COMPARATOR);
        emergencyMultisigKeys.sort(BtcECKey.PUBKEY_COMPARATOR);

        int expectedCsvValueLength = isRskip293Active ? BigInteger.valueOf(csvValue).toByteArray().length : 2;
        byte[] serializedCsvValue = isRskip293Active ?
            Utils.signedLongToByteArrayLE(csvValue) :
            Utils.unsignedLongToByteArrayBE(csvValue, expectedCsvValueLength);

        byte[] script = erpRedeemScript.getProgram();
        Assert.assertTrue(script.length > 0);

        int index = 0;

        // First byte should equal OP_NOTIF
        Assert.assertEquals(ScriptOpCodes.OP_NOTIF, script[index++]);

        // Next byte should equal M, from an M/N multisig
        int m = defaultMultisigKeys.size() / 2 + 1;
        Assert.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(m)), script[index++]);

        // Assert public keys
        for (BtcECKey key: defaultMultisigKeys) {
            byte[] pubkey = key.getPubKey();
            Assert.assertEquals(pubkey.length, script[index++]);
            for (byte b : pubkey) {
                Assert.assertEquals(b, script[index++]);
            }
        }

        // Next byte should equal N, from an M/N multisig
        int n = defaultMultisigKeys.size();
        Assert.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(n)), script[index++]);

        // Next byte should equal OP_ELSE
        Assert.assertEquals(ScriptOpCodes.OP_ELSE, script[index++]);

        // Next byte should equal csv value length
        Assert.assertEquals(expectedCsvValueLength, script[index++]);

        // Next bytes should equal the csv value in bytes
        for (int i = 0; i < expectedCsvValueLength; i++) {
            Assert.assertEquals(serializedCsvValue[i], script[index++]);
        }

        Assert.assertEquals(Integer.valueOf(ScriptOpCodes.OP_CHECKSEQUENCEVERIFY).byteValue(), script[index++]);
        Assert.assertEquals(ScriptOpCodes.OP_DROP, script[index++]);

        // Next byte should equal M, from an M/N multisig
        m = emergencyMultisigKeys.size() / 2 + 1;
        Assert.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(m)), script[index++]);

        for (BtcECKey key: emergencyMultisigKeys) {
            byte[] pubkey = key.getPubKey();
            Assert.assertEquals(Integer.valueOf(pubkey.length).byteValue(), script[index++]);
            for (byte b : pubkey) {
                Assert.assertEquals(b, script[index++]);
            }
        }

        // Next byte should equal N, from an M/N multisig
        n = emergencyMultisigKeys.size();
        Assert.assertEquals(ScriptOpCodes.getOpCode(String.valueOf(n)), script[index++]);

        Assert.assertEquals(ScriptOpCodes.OP_ENDIF, script[index++]);
        Assert.assertEquals(Integer.valueOf(ScriptOpCodes.OP_CHECKMULTISIG).byteValue(), script[index++]);
    }

    private static class RawGeneratedRedeemScript {
        List<BtcECKey> mainFed;
        List<BtcECKey> emergencyFed;
        Long timelock;
        Script script;

        @JsonCreator
        public RawGeneratedRedeemScript(@JsonProperty("mainFed") List<String> mainFed,
            @JsonProperty("emergencyFed") List<String> emergencyFed,
            @JsonProperty("timelock") Long timelock,
            @JsonProperty("script") String script) {
            this.mainFed = parseFed(mainFed);
            this.emergencyFed = parseFed(emergencyFed);
            this.timelock = timelock;
            this.script = new Script(Hex.decode(script));
        }

        private List<BtcECKey> parseFed(List<String> fed) {
            return fed.stream().map(Hex::decode).map(BtcECKey::fromPublicOnly).collect(Collectors.toList());
        }
    }
}
