pragma solidity 0.8.26;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "./Blacklist.sol";


contract ISTCoin is ERC20 {

    Blacklist public immutable blacklistContract;

    constructor (address blacklistAddress) ERC20("ISTCoin", "IST") {
        blacklistContract = Blacklist(blacklistAddress);
        _mint(msg.sender, 1000000 * (10 ** decimals()));
    }

    error Blacklisted(address account);

    function decimals() public pure override returns (uint8) {
        return 2;
    }

    function transfer(address to, uint256 value) public override returns (bool) {
        address sender = _msgSender();
        if(blacklistContract.isBlacklisted(sender)) {
            revert Blacklisted(sender);
        }
        _transfer(owner, to, value);
        return true;
    }

    function transferFrom(address from, address to, uint256 value) public override returns (bool) {
        if(blacklistContract.isBlacklisted(from)) {
            revert Blacklisted(from);
        }
        address spender = _msgSender();
        _spendAllowance(from, spender, value);
        _transfer(from, to, value);
        return true;
    }

}