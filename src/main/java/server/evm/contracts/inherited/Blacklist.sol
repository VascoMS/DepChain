pragma solidity 0.8.26;
import "@openzeppelin/contracts/access/Ownable.sol";

contract Blacklist is Ownable {
    mapping(address => bool) private blacklist;
    
    event AddedToBlacklist(address indexed account);
    event RemovedFromBlacklist(address indexed account);

    constructor() Ownable(msg.sender) {}
    
    function addToBlacklist(address _account) external onlyOwner returns (bool) {
        require(!blacklist[_account], "Already blacklisted");
        blacklist[_account] = true;
        emit AddedToBlacklist(_account);
        return true;
    }
    
    function removeFromBlacklist(address _account) external onlyOwner returns (bool) {
        require(blacklist[_account], "Not blacklisted");
        blacklist[_account] = false;
        emit RemovedFromBlacklist(_account);
        return true;
    }
    
    function isBlacklisted(address _account) public view returns (bool) {
        return blacklist[_account];
    }
}