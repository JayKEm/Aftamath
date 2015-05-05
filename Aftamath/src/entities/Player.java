package entities;

import handlers.SuperMob;
import handlers.Vars;
import main.House;
import main.Play;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;

public class Player extends SuperMob {

	public boolean stopPartnerDisabled = false;
	
	private double money, goalMoney;
	private House home;
	private NPC myPartner;
	//private String nickName;
	
	private double relationship;
	private double bravery;
	private double nicety;
	private float N, B, H, L;
	private String info;
	
	public Player(String name, String gender, String newID) {
		super(name, gender + newID, 0, 0, 0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, Vars.BIT_LAYER2);
		this.name = name;
		this.gender = gender;
		
		money = goalMoney = 1500.00;
//		myPartner = new Partner("Kira", "female", 1, x - width * 4, y + 30);
//		myPartner.setScript("script2");
//		myPartner = new Partner();
		home = new House();
		
		H = 1; L = 1; B = 1; N = 1;
	}
	
	//following methods are getters and setters

	public void update(float dt) {
		super.update(dt);
		updateMoney();
	}
	
	public void heal(double healVal){
		health += healVal * H;
		if (health > MAX_HEALTH) health = MAX_HEALTH;
	}
	
	public void damage(double damageVal){
		if (!dead) {
			if (!invulnerable) health -= H * damageVal;
			setAnimation(FLINCHING, Vars.ACTION_ANIMATION_RATE);
		}
		if (health <= 0 && !dead) die();
	}
	
	public void goOut(NPC newPartner, String info){
		myPartner = newPartner;
		this.info = info;
		relationship = 0;
		L = 0;
		
		gs.history.setFlag("hasPatner", true);
	}
	
	public void breakUp(){
		myPartner = new NPC();
		gs.history.setFlag("hasPatner", false);
//		relationship = 0;
//		L = 0;
	}
	
	public NPC getPartner(){ return myPartner; }
	public void resetRelationship(double d){ relationship = d; }
	public void setRelationship(double amount){ relationship += amount * L; }
	public void setLoveScale(float val){ L = val; }
	public float getLoveScale(){return L;}
	public double getRelationship(){ return relationship; }
	public String getPartnerInfo(){ return info; }
	public void resetPartnerInfo(String info){this.info=info;}
	
	public void addFollower(Mob m){ if (!followers.contains(m, true)) followers.add(m); }
	public void removeFollower(Mob m){ followers.removeValue(m, true); }
	public Array<Mob> getFollowers(){ return followers; }
	public int getFollowerIndex(Mob m) {
		Play.debugText = "" + followers; 
		return followers.indexOf(m, true) + 1; 
	}
	
	public void resetFollowers(Array <Mob> followers){
		for(Mob m : followers)
			this.followers.add(m);
	}
	
	public void addFunds(double amount){ goalMoney += amount; }
	
	private void updateMoney(){
		double dx = (goalMoney - money)/2;
//		if(dx < 1d && dx != 0) dx = 1 * (dx/Math.abs(dx));
		
		money += dx;
	}
	
	public double getMoney(){ return money; }
	public void resetMoney(double money){ this.money = money; }
	
	public void evict(){ home = new House(); }
	public void moveHome(House newHouse){ home = newHouse; }
	public House getHome(){  return home; }
	
	public void resetNiceness(double d){ nicety = d; }
	public void setNiceness(double d){ nicety += d * N; }
	public void setNicenessScale(float val){ N = val; }
	public double getNiceness() { return nicety; }
	public float getNicenessScale() { return N; }

	public void resetBravery(double d){ nicety = d; }
	public void setBravery(double d){ bravery += d * B; }
	public void setBraveryScale(float val){ B = val; }
	public double getBravery() { return bravery; }
	public float getBraveryScale() { return B; }
	
	public void setHealthScale(float val){ H = val; }
	
	public Player copy(){
		Player p = new Player(name, gender, ID);
		
		//copy stats
		p.resetMoney(money);
		p.resetHealth(health, MAX_HEALTH);
		p.setHealthScale(H);
		p.resetBravery(bravery);
		p.setBraveryScale(B);
		p.resetNiceness(nicety);
		p.setNicenessScale(N);
		p.goOut(myPartner.copy(), info);
		p.resetPartnerInfo(info);
		p.resetRelationship(relationship);
		p.setLoveScale(L);
		p.resetFollowers(followers);
		p.moveHome(p.getHome());
		
		p.setPowerType(type);
		p.resetLevel(level);
		p.setPlayState(gs);
		
		return p;
	}
	
	public void spawnPartner(Vector2 location){
		myPartner.spawn(location);
	}

	public void setGender(String gender) { this.gender = gender;}
}
