pragma solidity 0.8.26;

import "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import "./Blacklist.sol";

// Implemented using inheritance due to issues making cross contract calls with the Besu EVM implementation

contract ISTCoin is ERC20, Blacklist {
    mapping(address => bool) private _blacklist;
    constructor () ERC20("ISTCoin", "IST") Blacklist() {
        _mint(msg.sender, 1000000 * (10 ** decimals()));
    }

    error Blacklisted(address account);

    function decimals() public pure override returns (uint8) {
        return 2;
    }

    function transfer(address to, uint256 value) public override returns (bool) {
        address sender = _msgSender();
        if(isBlacklisted(sender)) {
            revert Blacklisted(sender);
        }
        _transfer(sender, to, value);
        return true;
    }

    function transferFrom(address from, address to, uint256 value) public override returns (bool) {
        if(isBlacklisted(from)) {
            revert Blacklisted(from);
        }
        address spender = _msgSender();
        _spendAllowance(from, spender, value);
        _transfer(from, to, value);
        return true;
    }

}