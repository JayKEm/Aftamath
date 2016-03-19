package entities;

import com.badlogic.gdx.graphics.Color;

import box2dLight.Light;

public class LightObj {
	private Light light;
	private String type;
	private boolean on, flickering, scheduled;
	private Color color;
	
	public LightObj(String type, Light light, boolean scheduled){
		this.light = light;
		this.type = type;
		this.scheduled = scheduled;
		color = new Color(light.getColor());
		on = true;
	}
	
	public void update(float dt){
		//TODO flicker handling
		if(flickering)
			flickering = false;
	}
	
	public void turnOff(){
//		light.setActive(false);
		light.setColor(Color.BLACK);
		on = false;
	}
	
	public void turnOn(){
//		light.setActive(true);
		on = true;
		switch(type){
		case "street":
			flicker();
		default:
			light.setColor(color);
			break;
		}
	}
	
	public boolean isOn() { return on; }
	public boolean isScheduled() { return scheduled; }
	public String getType(){ return type; }
	
	private void flicker(){
		flickering = true;
	}
	
	public String toString(){ return type+": "+on+"\tScheduled: "+scheduled; }

}