package main;

import static handlers.Vars.PPM;
import handlers.Camera;
import handlers.Evaluator;
import handlers.FadingSpriteBatch;
import handlers.GameStateManager;
import handlers.MyContactListener;
import handlers.MyInput;
import handlers.MyInput.Input;
import handlers.MyInputProcessor;
import handlers.Pair;
import handlers.PositionalAudio;
import handlers.Vars;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

import scenes.Scene;
import scenes.Script;
import scenes.Script.ScriptType;
import scenes.Song;
import box2dLight.RayHandler;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Box2DDebugRenderer;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;

import entities.CamBot;
import entities.Entity;
import entities.Entity.DamageType;
import entities.Ground;
import entities.HUD;
import entities.Mob;
import entities.Mob.AIState;
import entities.SpeechBubble;
import entities.SpeechBubble.PositionType;
import entities.TextBox;
import entities.Warp;

public class Main extends GameState {
	
	public Mob character;
	public Player player;
	public Warp warp;
	public Mob narrator;
	public HUD hud;
	public History history;
	public Evaluator evaluator;
	public Script currentScript;
	public InputState stateType, prevStateType;
	public int currentEmotion, dayState;
	public boolean paused, analyzing, choosing, waiting;
	public boolean warping, warped; //for changing between scenes
	public boolean speaking; //are letters currently being drawn individually
	public float dayTime, weatherTime, waitTime, totalWait, clockTime, playTime, keyIndexTime;
	
	private int sx, sy;
	private float speakTime, speakDelay = .025f;
	private ArrayDeque<Pair<String, Integer>> displayText; //all text pages to display
	private String[] speakText; //current text page displaying
	private String gameFile; //used to load a game
	private World world;
	private Scene scene, nextScene;
	private Color drawOverlay;
	private Array<Body> bodiesToRemove;
	private ArrayList<Entity> objects/*, UIobjects, UItoRemove*/;
	private ArrayList<PositionalAudio> sounds;
	private Box2DDebugRenderer b2dr;
	private InputState beforePause;
	private RayHandler rayHandler;
	private MyContactListener cl = new MyContactListener(this);
	private SpeechBubble[] choices;
	
	public static enum InputState{
		MOVE, //can move and interact
		MOVELISTEN, //listening to text and can move
		LISTEN, //listening to text only
		CHOICE, //choosing an option
		LOCKED, //lock all input; it's up to the script to unlock
		PAUSED, //in pause menu
		KEYBOARD, //input text
		GENDERCHOICE, //temporary; used to allow the player to chose their appearance
	}
	
	public static enum WeatherState{
		CLEAR, CLOUDY, RAINING, STORMY,
	}
	
	//day/night cycle variables
	private static final int DAY = 0;
	private static final int NOON = 1;
	private static final int NIGHT =2;
	private static final int TRANSITION_INTERVAL = /*60*/ 5;
	public static final float DAY_TIME = 24f*60f /*3*(TRANSITION_INTERVAL+5)*/;
	public static final float NOON_TIME = DAY_TIME/3f;
	public static final float NIGHT_TIME = 2*DAY_TIME/3f;
	
	//debug
	//use these for determining hard-coded values
	//please, don't keep them as hard-coded
	//private int debugX;
	public float debugY = Game.height/4, debugX=Game.width/4;
	
	public boolean dbRender 	= false, 
					rayHandling = false, 
					render 		= true, 
					dbtrender 	= false, 
					random;
//	private float ambient = .5f;
//	private int colorIndex;
//	private PointLight tstLight;
	
	public Main(GameStateManager gsm) {
		this(gsm, null);
	}
	
	public Main(GameStateManager gsm, String gameFile) {
		super(gsm);
		this.gameFile = gameFile;
	}
	
	public void create(){
		bodiesToRemove = new Array<>();
		history = new History();
		currentScript = null;
		evaluator = new Evaluator(this);
		
		stateType = prevStateType = InputState.MOVE;
		currentEmotion = Mob.NORMAL;
		dayState=DAY;
		paused=analyzing=choosing=waiting=warping=warped=speaking=false;
		dayTime = weatherTime= waitTime=totalWait=0;

//		sx=sy=0;
//		speakTime=0;
//		speakDelay = .025f;
		speakText = null;
//		dayTime = 3*NOON_TIME/2f;
		
		objects = new ArrayList<Entity>();
		sounds = new ArrayList<PositionalAudio>();
		world = new World(new Vector2 (0, Vars.GRAVITY), true);
		world.setContactListener(cl);
		b2dr = new Box2DDebugRenderer();
//		b2dr.setDrawVelocities(true);
		rayHandler = new RayHandler(world);
//		rayHandler.setAmbientLight(0);
		
		cam.reset();
		b2dCam.reset();
		load();
		
		speakTime = 0;
		displayText = new ArrayDeque<Pair<String, Integer>>();
		
		hud = new HUD(this, hudCam);
		handleDayCycle();
		handleWeather();
	}
	
	public void update(float dt) {
		super.update(dt);
		buttonTime += dt;
		hud.update(dt);
		
		if(stateType == InputState.KEYBOARD){
			keyIndexTime+=dt;
			if(keyIndexTime>=1)
				keyIndexTime = 0;
		}
		
		if (!paused){
			speakTime += dt;
			dayTime+=dt;
			clockTime = (dayTime+DAY_TIME/6f)%DAY_TIME;
			playTime+=dt;
			debugText = "";
			player.updateMoney();
			
			handleDayCycle();
			if(scene.outside) {
				weatherTime+=dt;
				handleWeather();
			}
			
			if(currentScript != null &&analyzing && !waiting) 
				currentScript.update();
			
			if (waiting){
				if(waitTime<totalWait) waitTime+=dt;
				else{
					waiting = false; 
					waitTime = totalWait = 0;
				}
			}

			handleInput();
			world.step(dt, 6, 2);
			
			for(PositionalAudio s : sounds)
				updateSound(s.location, s.sound);
			
			if(currentScript != null){
				float dx = character.getPosition().x - currentScript.getOwner().getPosition().x;
				
				//if player gets too far from whatever they're talking to
				if(Math.abs(dx) > 50/PPM && currentScript.limitDistance){
					currentScript = null;
					character.setInteractable(null);
					speaking = false;
					hud.hide();
					setStateType(InputState.MOVE);
				}
			}
 
			for (Entity e : objects){
				if (!(e instanceof Ground)) {
					e.update(dt);

					//kill object
					if (e.getBody() == null) {e.create();}
					if (e.getPixelPosition().x > scene.width + 50 || e.getPixelPosition().x < -50 || 
							e.getPixelPosition().y > scene.height + 100 || e.getPixelPosition().y < -50) {
						if (e.equals(character)){
							//Game Over
							//System.out.println("die"); 
						} else {
							bodiesToRemove.add(e.getBody());
						}
					}
				}
			}

			for (Body b : bodiesToRemove){
				if (b != null) 
					if (b.getUserData() != null){
						Object e = b.getUserData();
						if(cam.getFocus().equals(e))
							cam.removeFocus();
						objects.remove(e);
						world.destroyBody(b);
				}
			}

			bodiesToRemove.clear();

			if(warping){
				if (sb.getFadeType() == FadingSpriteBatch.FADE_IN)
					warp();
				if (!sb.fading && scene.equals(nextScene)){
					warping = false;
					nextScene = null;
				}
			}
			
			if(changingSong){
				if(!music.fading){
					music.dispose();
					setSong(nextSong);
					nextSong=null;
					changingSong = false;
				}
			}
			
			if(tempSong)
				if(!music.isPlaying())
					removeTempSong();
			
			// moving point light
//			tstLight.setPosition(character.getPosition());
//			rayHandler.setLightMapRendering(false);
		} else {
			if (!quitting)
				handleInput();
		}
		
		
//		debugText= "Volume: "+music.getVolume();
//		debugText= "next: "+nextSong+"     "+music;
		debugText= Vars.formatDayTime(clockTime, false)+"    Play Time: "+Vars.formatTime(playTime);
//		debugText+= "/l/l"+drawOverlay.r+"/l"+drawOverlay.g+"/l"+drawOverlay.b;
		debugText+= "/lState: "+(stateType);
		debugText+="/linput: "+Game.getInput()+"   inputIndex: "+Game.inputIndex;
//		for(Entity e: objects) if(!e.ID.toLowerCase().equals("tiledobject"))debugText+="/l"+e;
		debugText+="/lATTACKABLES: "+character.getAttackables();
		
		if(currentScript!=null){
			debugText+= "/lplayerType: "+(currentScript.getVariable("playertype"))+"/l";
			debugText+= "/lfocedPause: "+(currentScript.forcedPause);
//			debugText+= "/lmoving: "+(cam.moving);
			debugText+= "/ldialog: "+(currentScript.dialog);
			debugText+= "/lspeaking: "+(speaking);
//			debugText+= "/lActObj: "+(currentScript.getActiveObject());
		}
		
//		debugText+= "/l/lcontrolled: "+character.controlled;
//		debugText+= "/lIdle Time: "+ Vars.formatTime(character.getIdleTime()) + "    Idled: "+character.getTimesIdled();
		debugText +="/l/l"+ character.getName() + " x: " + (int) (character.getPosition().x*PPM) + 
				"    y: " + ((int) (character.getPosition().y*PPM) - character.height);
//		debugText +="/l"+cam.getCharacter().getName() + " x: " + (int) (cam.position.x) + "    y: " + ((int) (cam.position.y));

		cam.locate(dt);
		if(quitting)
			quit();
	}
	
	public void handleWeather(){
		
	}
	
	// modify colors and sounds according to day time
	public void handleDayCycle(){
		if(dayTime>NOON_TIME){
			dayState = NOON;
		}if(dayTime>NIGHT_TIME){
			dayState = NIGHT;
		}if(dayTime>DAY_TIME){
			dayState = DAY;
			dayTime=0;
			
			if (scene.outside);
				//if (street lights are on)
					//turn off lights 
		} if(dayTime>NIGHT_TIME-TRANSITION_INTERVAL && scene.outside){
			//if(street lights are off)
				//turn on lights
		}
		
		float t=dayTime,i;
		if(scene.outside){
			switch(dayState){
			case DAY:
				drawOverlay = Vars.DAYLIGHT;
				if(sb.isDrawingOverlay()) sb.setOverlayDraw(false);
				break;
			case NOON:
				i = DAY_TIME/12f;
				drawOverlay = Vars.DAYLIGHT;
				
				if(t>NIGHT_TIME-i){
					if(t>NIGHT_TIME-i)
						drawOverlay = Vars.blendColors(t, NIGHT_TIME-i, NIGHT_TIME-i+i/4f, 
								Vars.DAYLIGHT, Vars.SUNSET_GOLD);
					if(t>NIGHT_TIME-i+i/4f)
						drawOverlay = Vars.blendColors(t, NIGHT_TIME-i+i/4f, NIGHT_TIME-i+2*i/4f, 
								Vars.SUNSET_GOLD, Vars.SUNSET_ORANGE);
					if(t>NIGHT_TIME-i+2*i/4f)
						drawOverlay = Vars.blendColors(t, NIGHT_TIME-i+2*i/4f, NIGHT_TIME-i+3*i/4f, 
								Vars.SUNSET_ORANGE, Vars.SUNSET_MAGENTA);
					if(t>NIGHT_TIME-i+3*i/4f)
						drawOverlay = Vars.blendColors(t, NIGHT_TIME-i+3*i/4f, NIGHT_TIME, 
								Vars.SUNSET_MAGENTA, Vars.NIGHT);
					sb.setOverlay(drawOverlay);
					if(!sb.isDrawingOverlay())
						sb.setOverlayDraw(true);
				}
				
				break;
			case NIGHT:
				i = TRANSITION_INTERVAL;
				
				if(t>DAY_TIME-i){
					if(t>DAY_TIME-i)
						drawOverlay = Vars.blendColors(t, DAY_TIME-i, DAY_TIME-i/2f, 
								Vars.NIGHT, Vars.SUNRISE);
					if(t>DAY_TIME-i/2f)
						drawOverlay = Vars.blendColors(t, DAY_TIME-i/2f, DAY_TIME, 
								Vars.SUNRISE, Vars.DAYLIGHT);
				} else
					drawOverlay = Vars.NIGHT;
				
				sb.setOverlay(drawOverlay);
				if(!sb.isDrawingOverlay())
					sb.setOverlayDraw(true);
				break;
			}
			
//			rayHandler.setAmbientLight(ambient);
		}
		
		if(!random)
			if(!tempSong)
				if(!scene.DEFAULT_SONG[dayState].equals(music))
					changeSong(scene.DEFAULT_SONG[dayState]);
	}
	
	public void render() {
//		Gdx.gl20.glClearColor(skyColor.r, skyColor.b, skyColor.g, skyColor.a);
		Gdx.gl20.glClear(GL20.GL_COLOR_BUFFER_BIT);
		
			sb.setProjectionMatrix(cam.combined);
			rayHandler.setCombinedMatrix(cam.combined);
			scene.renderBG(sb);

		if(render){
			scene.renderEnvironment(cam);
			
			sb.begin();

			int i = -1;
			Entity e;
			for (int x = 0; x<objects.size(); x++){
				e = objects.get(x);
				if(e instanceof SpeechBubble || e instanceof TextBox){
					i = x;
					break;
				}
				e.render(sb);
			}
			sb.end();
		
			scene.renderFG(sb);
			
			if(i>=0){
				sb.begin();
				for (int x = i; x<objects.size(); x++)
					objects.get(x).render(sb);
				sb.end();
			}
		}
		
		if (speaking) speak();
		if (rayHandling) rayHandler.updateAndRender();
		
		boolean o = sb.isDrawingOverlay();
		if(o) sb.setOverlayDraw(false);
		
		b2dCam.setPosition(character.getPosition().x, character.getPosition().y + 15 / PPM);
		b2dCam.update();
		if (dbRender) b2dr.render(world, b2dCam.combined);
		hud.render(sb, currentEmotion);
		
		if(stateType == InputState.KEYBOARD){
			sb.begin();
			hud.drawInputBG(sb);
			drawString(sb, Game.getInput(), Game.width/4 - Game.getInput().length()*font[0].getRegionWidth()/2,
					195);
			if(keyIndexTime<.5f)
				drawString(sb, "_", Game.width/4 - Game.getInput().length()*font[0].getRegionWidth()/2 +
						Game.inputIndex*font[0].getRegionWidth(), 195);
			sb.end();
		}
		
		if(dbtrender) updateDebugText();
		
		if(o) sb.setOverlayDraw(true);
	}
	
	public void updateDebugText() {
		sb.begin();
			drawString(sb, debugText, 2, Game.height/2 - font[0].getRegionHeight() - 2);
		sb.end();
	}

	public void handleInput() {
		if(MyInput.isPressed(Input.DEBUG_UP)) {
//			light += .1f; rayHandler.setAmbientLight(light);
			player.addFunds(100d);
		}
		
		if(MyInput.isPressed(Input.DEBUG_DOWN)){
//			light -= .1f; rayHandler.setAmbientLight(light);
			player.addFunds(-100d);
		}
		
		if(MyInput.isDown(Input.DEBUG_LEFT)) {
//			if(colorIndex>0) 
//				colorIndex--; 
//			rayHandler.setAmbientLight(Vars.COLORS.get(colorIndex)); 
//			rayHandler.setAmbientLight(ambient);
			debugX-=1;
		}
		
		if(MyInput.isDown(Input.DEBUG_RIGHT)) {
//			if(colorIndex<Vars.COLORS.size -1) 
//				colorIndex++; 
//			rayHandler.setAmbientLight(Vars.COLORS.get(colorIndex)); 
//			rayHandler.setAmbientLight(ambient);
//			debugX+=.2f;
//			System.out.println(debugX);
			debugX+=1;
		}
		
		if (MyInput.isDown(Input.DEBUG_LEFT2)) {
			debugY+=1;
		}
		if (MyInput.isDown(Input.DEBUG_RIGHT2)) {
			debugY-=1;
		}
		
		if(MyInput.isPressed(Input.DEBUG_CENTER)) {
			random=true;
			changeSong(new Song(Game.SONG_LIST.get((int) (Math.random()*(Game.SONG_LIST.size)))));
//			dayTime+=1.5f;
		}
		
		if(MyInput.isDown(Input.ZOOM_OUT /*|| Gdx.input.getInputProcessor().scrolled(-1)*/)) {
			cam.zoom+=.01;
			b2dCam.zoom+=.01;
		}
		if(MyInput.isDown(Input.ZOOM_IN /*|| Gdx.input.getInputProcessor().scrolled(1)*/)) {
			cam.zoom-=.01;
			b2dCam.zoom-=.01;
		}
		if(MyInput.isPressed(Input.LIGHTS)) rayHandling = !rayHandling ;
		if(MyInput.isPressed(Input.COLLISION)) dbRender = !dbRender ;
		if(MyInput.isPressed(Input.DEBUG)) character.respawn();
		if(MyInput.isPressed(Input.DEBUG2)) render=!render;
		if(MyInput.isPressed(Input.DEBUG_TEXT)) dbtrender=!dbtrender;
		
		if (paused){
			if (stateType == InputState.PAUSED) {
				if(MyInput.isPressed(Input.PAUSE)) unpause();
				if(MyInput.isPressed(Input.JUMP)|| MyInput.isPressed(Input.ENTER) && !gsm.isFading()){
					try {
						Method m = Main.class.getMethod(Vars.formatMethodName(menuOptions[menuIndex[0]][menuIndex[1]]));
						m.invoke(this);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				
				if(buttonTime >= DELAY){
					if(MyInput.isDown(Input.DOWN)){
						if(menuIndex[1] < menuMaxY){
							menuIndex[1]++;
							//play menu sound
						} else {
							menuIndex[1] = 0;
						}
						buttonTime = 0;
					}
					
					if(MyInput.isDown(Input.UP)){
						if(menuIndex[1] > 0){
							menuIndex[1]--;
							//play menu sound
						} else {
							menuIndex[1] = menuMaxY;
						}
						buttonTime = 0;
					}
					
					if(MyInput.isDown(Input.RIGHT)){
						if(menuIndex[0] < menuMaxX){
							menuIndex[0]++;
							//play menu sound
						} else {
							//play menu invalid sound
						}
						buttonTime = 0;
					}
					
					if(MyInput.isDown(Input.LEFT)){
						if(menuIndex[0] > 0){
							menuIndex[0]--;
							//play menu sound
						} else {
							//play menu invalid sound
						}
						buttonTime = 0;
					}
				}
			}
		} else{ 
			switch (stateType){
			case KEYBOARD:
				if(MyInput.isPressed(Input.PAUSE) && !quitting) pause();
				if(MyInput.isPressed(Input.CAPS)) MyInput.isCaps = !MyInput.isCaps;
				if(buttonTime>=.15f){
					if(MyInput.isPressed(Input.ENTER)){
						buttonTime=0;
						//isNameAppropiate(Game.input);
						if(!Game.getInput().isEmpty()) currentScript.applyInput();
					}
					
					if(MyInput.isDown(Input.DOWN)){
						buttonTime=0;
						Game.removeInputChar();
					}
					
					if(MyInput.isDown(Input.JUMP)){
						buttonTime=0;
						Game.addInputChar(" ");
					}
					
					if(MyInput.isDown(Input.LEFT)){
						buttonTime=0;
						if(Game.inputIndex>0)
							Game.inputIndex--;
					}
					
					if(MyInput.isDown(Input.RIGHT)){
						buttonTime=0;
						if(Game.inputIndex<Game.getInput().length()-2)
							Game.inputIndex++;
					}
				}
				break;
			case GENDERCHOICE:
				if(MyInput.isPressed(Input.PAUSE) && !quitting) pause();
				int index = (int) currentScript.getVariable("playertype");
				
				if(MyInput.isPressed(Input.LEFT)){
					if(index>1)index--;
					else index = 4;
					
					character.ID = character.getGender() + "player" + index;
					character.loadSprite();
					currentScript.setVariable("playertype", index);
				}
				
				if(MyInput.isPressed(Input.RIGHT)){
					if(index<4) index++;
					else index =1;

					character.ID = character.getGender() + "player" + index;
					character.loadSprite();
					currentScript.setVariable("playertype", index);
				}
				
				if(MyInput.isPressed(Input.ENTER) || MyInput.isPressed(Input.JUMP)){
					currentScript.paused = false;
					setStateType(prevStateType);
				}
				
				break;
			case LOCKED:
				if(MyInput.isPressed(Input.PAUSE) && !quitting) pause();
				break;
			case MOVE:
				if(MyInput.isPressed(Input.PAUSE) && !quitting) pause();
				if(cam.focusing||warping||quitting||character.dead||waiting||character.frozen) return;
				if(MyInput.isPressed(Input.JUMP)) character.jump();
				if(MyInput.isDown(Input.UP)) {
					if(character.canWarp && character.isOnGround() && !character.snoozing && 
							!character.getWarp().instant) {
						initWarp(character.getWarp());
					}
					else if(character.canClimb) character.climb();
					else character.lookUp();
				}
				if(MyInput.isDown(Input.DOWN)) 
					if(character.canClimb)
						character.descend();
					else character.lookDown();
				if(MyInput.isPressed(Input.USE)) partnerFollow();
				if(MyInput.isPressed(Input.INTERACT)) 
					triggerScript(character.interact());

				if(MyInput.isDown(Input.LEFT)) character.left();
				if(MyInput.isDown(Input.RIGHT)) character.right();
				if(MyInput.isDown(Input.RUN)) {character.run();}
				if(MyInput.isPressed(Input.ATTACK)) {
					character.attack();
				}
				break;
			case MOVELISTEN:
				if(MyInput.isPressed(Input.PAUSE)) pause();
				if(cam.moving||cam.focusing||warping||quitting||character.dead||waiting||character.frozen) return;
				if(MyInput.isDown(Input.UP)) character.climb();
				if(MyInput.isDown(Input.DOWN)) character.descend();
				if(MyInput.isDown(Input.LEFT)) character.left();
				if(MyInput.isDown(Input.RIGHT)) character.right();
				if(MyInput.isPressed(Input.JUMP) && !speaking && buttonTime >= DELAY) {
					playSound(character.getPosition(), "ok1");

					if(displayText.isEmpty()) {
						if (currentScript != null)
							currentScript.paused = currentScript.forcedPause = 
							currentScript.dialog = false;
					} else {
						speakDelay = .2f;
						speakTime = 0;
						speak();
					}
				} else if (MyInput.isPressed(Input.JUMP) && speaking){
					buttonTime = 0;
					speaking = hud.fillSpeech(speakText);
				}
				break;
			case LISTEN:
				if(MyInput.isPressed(Input.PAUSE) && !quitting) pause();
				
				if(currentScript!=null)
					if(currentScript.dialog && MyInput.isPressed(Input.JUMP)){
						if(!speaking && buttonTime >= DELAY){
							playSound(character.getPosition(), "ok1");
							if(currentScript.getActiveObject() instanceof TextBox){
								TextBox t = (TextBox) currentScript.getActiveObject();
								t.kill();

								currentScript.paused = currentScript.forcedPause = false;
								currentScript.setActiveObj(new Entity());
								setStateType(prevStateType);
							} else {
								
								// This line of code is essential; it gives the game just enough time
								// so that the script doesn't skip more than once to the end
								int x12 = 1; if (x12==1) x12=2;
									
									
								if(displayText.isEmpty()) {
									if (currentScript != null) {
										currentScript.paused = currentScript.forcedPause =
												currentScript.dialog = false;
									} else {
										hud.hide();
										setStateType(InputState.MOVE);
									}
								} else {
									speakDelay = .2f;
									speakTime = 0;
									speak();
								}
							}
						} else if (speaking){
							buttonTime = 0;
							speaking = hud.fillSpeech(speakText);
						}	
					}
				break;
			case CHOICE:
				int prevIndex = choiceIndex;
				if(MyInput.isPressed(Input.PAUSE) && !quitting) pause();
				if (buttonTime >= buttonDelay){
					if(MyInput.isDown(Input.LEFT)){
						buttonTime = 0;
						choiceIndex++;
						if (choiceIndex >= choices.length)
							choiceIndex = 0;

						playSound("text1");
						choices[choiceIndex].expand();
						choices[prevIndex].collapse();
					} else if (MyInput.isDown(Input.RIGHT)){
						buttonTime = 0;
						choiceIndex--;
						if (choiceIndex < 0)
							choiceIndex = choices.length - 1;

						playSound("text1");
						choices[choiceIndex].expand();
						choices[prevIndex].collapse();
					} else if(MyInput.isPressed(Input.JUMP)){
//						setStateType(prevStateType);
						playSound("ok1");
//						wait(.5f);

						currentScript.paused = choosing = false;
						currentScript.getChoiceIndex(choices[choiceIndex].getMessage());

						//delete speechBubbles from world
						for (SpeechBubble b : choices)
							bodiesToRemove.add(b.getBody()); 
					}
				}

				break;
			default:
				break;
			}
		}
	}
	
	private void partnerFollow(){
		if(player.getPartner()!=null){
			if(player.getPartner().getName() != null){
				if(getMob(player.getPartner().getName())!=null){
					setStateType(InputState.LISTEN);

					if (player.stopPartnerDisabled) {
						displayText.add(new Pair<>("No, I'm coming with you.", Mob.NORMAL));
						hud.changeFace(player.getPartner());
						character.faceObject(player.getPartner());
						speak();
					} else if(player.getPartner().getState() == AIState.FOLLOWING) {
						displayText.add(new Pair<>("I'll just stay here.", Mob.NORMAL));
						hud.changeFace(player.getPartner());
						speak();
						player.getPartner().stay();
					}
					else {
						if(player.getRelationship()<-2){ 
							displayText.add(new Pair<>("No way. I'm staying here.", Mob.MAD));
							character.faceObject(player.getPartner());
						} else if(player.getPartner().getGender().equals("female")) displayText.add(new Pair<>("Coming!", Mob.HAPPY));
						else displayText.add(new Pair<>("On my way.", Mob.NORMAL));

						hud.changeFace(player.getPartner());
						speak();
						player.getPartner().follow(character);
					}
				}else{
					String partner;
					if (player.getPartner().getGender().equals(Mob.MALE)) partner = "boyfriend";
					else partner = "girlfriend";
					displayText.add(new Pair<>("Your "+partner+" isn't here at the moment.", Mob.NORMAL));
					hud.changeFace(narrator);
					speak();
					setStateType(InputState.LISTEN);
				}
			}
		} else {
			displayText.add(new Pair<>("You ain't got nobody to follow you!", Mob.NORMAL));
			hud.changeFace(narrator);
			speak();
			setStateType(InputState.LISTEN);
		}
	}
	
	public void changeSong(Song newSong){
		if(changingSong)return;
		if(music!=null){
			nextSong = newSong;
			music.fadeOut();
			changingSong = true;
		} else
			music = newSong;
	}
	
	public void positionPlayer(Entity obj){
		float min = 18;
		float dx = (character.getPosition().x - obj.getPosition().x) * Vars.PPM;
		float gx = min * dx / Math.abs(dx) - dx ;
		
		if (Math.abs(dx) < min - 2){
			setStateType(InputState.LOCKED);
			character.positioning = true;
			character.setPositioningFocus(character.getInteractable());
			character.setGoal(gx);
			currentScript.setActiveObj(character);
		} else {
			character.faceObject(character.getInteractable());
			character.controlled = false;
		}
	}
	
	//show choices in a circle around the player
	public void displayChoice(Array<Script.Option> options){
		final float h = 30 / PPM;
		float x, y, theta;
		int c = options.size;
		choices = new SpeechBubble[c];
		choiceIndex = 0;
		
		Script.Option o;
		PositionType positioning;
		for (int i = 0; i < c; i++){
			o = options.get(i);
			theta = (float) (i * 2 * Math.PI / c);
			x = (float) (h * Math.cos(theta) + character.getPosition().x) * PPM;
			y = (float) (h * Math.sin(theta) + character.getPosition().y) * PPM + 15;
			
			if((theta>Math.PI/2-Math.PI/20 && theta<Math.PI/2+Math.PI/20) ||
					(theta>3*Math.PI/2-Math.PI/20 && theta<3*Math.PI/2+Math.PI/20)) 
				positioning = PositionType.CENTERED;
			else if((theta>=0 && theta<Math.PI/2-Math.PI/20) ||
					(theta<=2*Math.PI && theta>3*Math.PI/2+Math.PI/20))
				positioning = PositionType.LEFT_MARGIN;
			else positioning = PositionType.RIGHT_MARGIN;
			choices[i] = new SpeechBubble(character, x, y, o.type, o.message, positioning);
		}
		choices[0].expand();
	}
	
	public void dispose() { 
		if(music!=null)
			music.dispose();
	}
	
	public void speak(){
		if (!speaking && !displayText.isEmpty()) {
			speaking = true;

			Pair<String, Integer> current = displayText.poll();
			String line = current.getKey();
			currentEmotion = current.getValue();
			
			speakText = line.split("/l");
			hud.createSpeech(speakText);

			sx = 0;
			sy = 0;

			if(!hud.raised) 
				hud.show();
			
			music.fadeOut(Game.musicVolume*2f/3f);
		}
		
		//show text per character
		if (hud.moving == 0){
			if (speakTime >= speakDelay) {
				speakTime = 0;
				if(hud.raised) speakDelay = .020f;
				else speakDelay = 2f;

				if(speakText[sy].length()>0){
					char c = speakText[sy].charAt(sx);
					if (c == ".".charAt(0) || c == ",".charAt(0)|| c == "!".charAt(0)|| c == "?".charAt(0)) 
						speakDelay = .25f;
					if(c != "~".charAt(0)){
						hud.addChar(sy, c);
						Gdx.audio.newSound(new FileHandle("assets/sounds/text1.wav"))
						.play(Game.soundVolume * .9f, (float) Math.random()*.15f + .9f, 1);
					} else speakDelay = .75f;

					sx++;
					if (sx == speakText[sy].length()) {sx = 0; sy++; }
					if (sy == speakText.length) {
						speaking = false;
						speakText = null;
						
						music.fadeIn(Game.musicVolume);
						
						if (currentScript != null){
							if(currentScript.peek() != null)
								if(currentScript.peek().toLowerCase().equals("choice")
										&& displayText.isEmpty())
									currentScript.readNext();
						}
					}
				}else
					sy++;
			}
		}
	}
	
	public void pause(){
		if (music!=null) 
			if (music.isPlaying()) 
				music.fadeOut(false);
		
		beforePause = stateType;
		setStateType(InputState.PAUSED);
		
		menuOptions = new String[][] {{"Resume", "Stats", "Save Game", "Load Game", 
			"Options", "Quit to Menu", "Quit"}}; 
		
		paused = true;
		menuMaxY = menuOptions[0].length - 1;
		menuMaxX = 0;
		menuIndex[0] = 0;
		menuIndex[1] = 0;
	}
	
	public void stats(){
		//change menu to display stats
	}
	
	public void saveGame() {
		//save game menu
	}
	
	public void loadGame() {
		//load a saved game menu
		//display option to save before loading, with options: "save; don't save; cancel"
		super.loadGame();
	}
	
	public void quitToMenu(){
		//display option to save before exit, with options: "save and exit; exit; cancel"
		gsm.setState(GameStateManager.TITLE, true);
	}
	
	public void quit() {
		//display option to save before exit, with options: "save and exit; exit; cancel"
		super.quit();
	}
	
	public void unpause(){
		setStateType(beforePause);
		paused = false;
		
		if (music!=null) 
			music.fadeIn();
	}
	
	public void initWarp(Warp warp){
		warping = true;
		warped = false;
		this.warp = warp;
		nextScene = warp.getNext();
//		scene.saveLevel();

		sb.fade();
		if(nextScene.newSong)
			changeSong(nextScene.DEFAULT_SONG[dayState]);
	}
	
	public void warp(){
		if(warped) return;
		
		random=false;
		destroyBodies();
		scene = nextScene;
		scene.create();
		createPlayer(new Vector2(warp.getLink().x, warp.getLink().y+character.rh));
		initEntities();
		cam.setBounds(Vars.TILE_SIZE*4, (scene.width-Vars.TILE_SIZE*4), 0, scene.height);
		b2dCam.setBounds((Vars.TILE_SIZE*4)/PPM, (scene.width-Vars.TILE_SIZE*4)/PPM, 0, scene.height/PPM);
		
		warped = true;
	}
	
	public void wait(float time){
		if(busy()) return;
		totalWait = time;
		waiting = true;
		waitTime = 0;
//		if(hud.raised) hud.clearSpeech();
	}
	
	public boolean busy(){
		return false;
	}
	
	public void setDispText(ArrayDeque<Pair<String, Integer>> dispText) { displayText = dispText;}
	public void setStateType(InputState type) {
		if(type == InputState.KEYBOARD)
			((MyInputProcessor) Gdx.input.getInputProcessor()).keyboardMode();
		else
			((MyInputProcessor) Gdx.input.getInputProcessor()).gameMode();
		if(stateType!=InputState.PAUSED)
			prevStateType = stateType;
		stateType = type; 
	}
	
	public InputState getStateType(){ return stateType; }
	public SpeechBubble[] getChoices(){ return choices; }

	public void addSound(PositionalAudio s){ sounds.add(s); }
	public void addObject(Entity e){ 
		if(!exists(e)){
			objects.add(e); 
			sortObjects();
		}
	}
	public void addBodyToRemove(Body b){ bodiesToRemove.add(b); }
	
	public void load(){
		narrator = new Mob("Narrator", "narrator1", 0, 0, 0, Vars.BIT_LAYER1);
		player = new Player(this);
		if(gameFile==null){
//		if(gameFile!=null){
			scene= new Scene(world,this,"Street");
//			scene = new Scene(world, this, "room1_1");
			scene.setRayHandler(rayHandler);
			setSong(scene.DEFAULT_SONG[dayState]);
			music.pause();
			scene.create();
		
			if(scene.getCBSP()!=null)
				createEmptyPlayer(scene.getCBSP());
			else
				createEmptyPlayer(scene.getSpawnPoint());
			 
//			tstLight = new PointLight(rayHandler, Vars.LIGHT_RAYS, Color.RED, 100, 0, 0);
//			tstLight.attachToBody(character.getBody(), 0, 0);
			triggerScript("intro");
		} else {
			scene= new Scene(world,this,"Street");
			//scene = load recent scene from
			setSong(scene.DEFAULT_SONG[dayState]);
			scene.setRayHandler(rayHandler);
			scene.create();
			
			character = new Mob("TestName", "maleplayer3",scene.getSpawnPoint() , Vars.BIT_PLAYER_LAYER);
			createPlayer(scene.getSpawnPoint());
		}
		
		initEntities();
		cam.bind(scene, false);
		b2dCam.bind(scene, true);
		world.setGravity(scene.getGravity());
	}
	
	public void createPlayer(Vector2 location){
		String gender = character.ID.substring(0, character.ID.indexOf("player"));
		character.setGender(gender);
		
		cam.zoom = Camera.ZOOM_NORMAL;
		b2dCam.zoom = Camera.ZOOM_NORMAL;
		
		character.setPosition(location);
		character.setGameState(this);
		character.create();
		cam.setCharacter(character); 
		b2dCam.setCharacter(character);
		cam.locate(Vars.DT);
		sortObjects();
	}
	
	public void createEmptyPlayer(Vector2 location){
		//debug
//		cam.setLock(false);
//		b2dCam.setLock(false);
//		cam.zoom = Camera.ZOOM_FAR;
//		b2dCam.zoom = Camera.ZOOM_FAR;
		
		character = new CamBot(0, 0);
		cam.zoom = Camera.ZOOM_NORMAL;
		b2dCam.zoom = Camera.ZOOM_NORMAL;
		
		character.setPosition(location);
		character.setGameState(this);
		character.create();
		cam.setCharacter(character); 
		b2dCam.setCharacter(character);
		cam.locate(Vars.DT);
		sortObjects();
	}
	
	public void switchCharacter(Mob c){
		character = c;
		cam.setCharacter(character);
		b2dCam.setCharacter(character);
	}
	
	public void triggerScript(String src){
		Script s = new Script(src, ScriptType.DIALOGUE, this, character);
		if (s.ID!=null)
			triggerScript(s);
	}
	
	public void triggerScript(Script script){
		currentScript = script;
		
		if (analyzing) return;
		if (currentScript != null) {
			analyzing = true;
			for (Entity e : objects)
				if (e instanceof SpeechBubble)
					addBodyToRemove(e.getBody()); //remove talking speech bubble
			script.analyze();
			if (currentScript.limitDistance) {
				setStateType(InputState.MOVELISTEN);
			} else {
				if(character.getInteractable()!=null)
					positionPlayer(character.getInteractable());
			}
		}
	}
	
	//add all of the scene's entities on init and create them
	private void initEntities() {
		removeAllObjects();
		objects.addAll(scene.getInitEntities());
		objects.add(character);
		
		//this should be changed to happen when the game checks if the player's
		//partner was last saved on the same level (part of global mobs)
		if(player.getPartner()!=null)
			if (player.getPartner().getName() != null) 
				objects.add(player.getPartner());

		for (Entity d : objects){
			d.setGameState(this);
			if(!d.equals(character))
					d.create();
		}
		
		sortObjects();
	}
	
	public Mob getMob(String name) {
		for (Entity e : objects)
			if(e instanceof Mob){
				Mob m = (Mob) e;
				if(m.getName().equals(name))
					return m;
			}
		return null;
	}
	
	public void destroyBodies(){
		Array<Body> tmp = new Array<Body>();
		world.getBodies(tmp);
		for(Body b : tmp)
			world.destroyBody(b);
	}
	
	public void removeAllObjects(){
		for(PositionalAudio s : sounds)
			s.sound.stop();
		sounds = new ArrayList<PositionalAudio>();
		objects = new ArrayList<Entity>();
	}
	
	public Scene getScene(){ return scene; }
	public World getWorld(){ return world; }
	public HUD getHud() { return hud; }
	public Camera getCam(){ return cam; }

	public int count(Entity c){
		int i = 0;
		for (Entity e : objects) 
			if (e.getClass().equals(c.getClass())) 
				i++;
		return i;
	}

	//sort by objects' layer
	public void sortObjects(){
//		printObjects();
		Collections.sort(objects, new Comparator<Entity>(){
			public int compare(Entity o1, Entity o2) {
				return o1.compareTo(o2);
			}
		});
		
		//add all UI objects to end of list
		ArrayList<Entity> tmp = new ArrayList<>();
		for(Entity e: objects)
			if(e instanceof SpeechBubble || e instanceof TextBox)
				tmp.add(e);
		objects.removeAll(tmp);
		objects.addAll(tmp);
		
//		printObjects();
	}
	
	public boolean exists(Entity obj){
		for(Entity e: objects)
			if(e.equals(obj))
				return true;
		return false;
	}
	
	public void printObjects() {
		for(Entity e:objects){
			debugText+="/l"+e.ID;
			System.out.println(e);
		}
	}

	public ArrayList<Entity> getObjects(){ return objects;	}
	
	public Color getColorOverlay(){
		// should be dependant on time;
		return new Color(2,2,2,Vars.ALPHA);
	}
	
	//contains all data related to the player
	public class Player {

		public boolean stopPartnerDisabled = false;
		
		private double money, goalMoney;
		private House home;
		private Mob myPartner;
		//private String nickName;
		private Main main;
		private double relationship;
		private double bravery;
		private double nicety;
		private float N, B, L;
		private String info;
		
		private HashMap<DamageType, Integer> typeCounter;
		
		public Player(Main main) {
			this.main = main;
			money = goalMoney = 1500.00;
			home = new House();
			
			L = 1; B = 1; N = 1;
			typeCounter = new HashMap<>();
		}
		
		//make the player do some random SUPA power
		//if the player has done this enough times, the most common power will become their permanent power
		//if they don't have one already
		public void doRandomPower(Vector2 target){
			int type = (int) Math.random()*(DamageType.values().length-1);
			DamageType dT = DamageType.values()[type];
			
			character.setPowerType(dT);
			typeCounter.put(dT, typeCounter.get(dT)+1);
			
			int max = 0, c;
			for(DamageType d : typeCounter.keySet()){
				c = typeCounter.get(d);
				if(c>=max) {
					max = c; 
				}
			}
			
			main.character.powerAttack(target);

			if(max<3)
				character.setPowerType(DamageType.PHYSICAL);
			else
				character.setPowerType(dT);
		}
		
		public void follow(Entity focus) {
//			this.focus = focus;
//			controlled = true;
//			controlledAction = Action.WALKING;
			
		}
		
		
		//following methods are getters and setters
		
		public void goOut(Mob newPartner, String info){
			myPartner = newPartner;
			this.info = info;
			relationship = 0;
			L = 0;
			
			main.history.setFlag("hasPatner", true);
		}
		
		public void breakUp(){
			myPartner = new Mob();
			main.history.setFlag("hasPatner", false);
//			relationship = 0;
//			L = 0;
		}
		
		public Mob getPartner(){ return myPartner; }
		public void resetRelationship(double d){ relationship = d; }
		public void setRelationship(double amount){ relationship += amount * L; }
		public void setLoveScale(float val){ L = val; }
		public float getLoveScale(){return L;}
		public double getRelationship(){ return relationship; }
		public String getPartnerInfo(){ return info; }
		public void resetPartnerInfo(String info){this.info=info;}
		
		public void resetFollowers(Array <Mob> followers){
			for(Mob m : followers)
				main.character.getFollowers().add(m);
		}
		
		public void addFunds(double amount){ goalMoney += amount; System.out.println(goalMoney); }
		public void updateMoney(){
			double dx = (goalMoney - money)/2;
//			if(dx < 1d && dx != 0) dx = 1 * (dx/Math.abs(dx));
			
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
		
		public void spawnPartner(Vector2 location){
			myPartner.spawn(location);
		}

		public void setTypeCounter(HashMap<DamageType, Integer> tc){ typeCounter = tc; }
	}
	
	//class that contains minor handling for all events and flags
	public class History {

		private HashSet<Pair<String, String>> eventList;
		private HashMap<String, Boolean> flagList;
		private HashMap<String, Object> variableList;
		
		public History(){
			eventList = new HashSet<>();
			flagList = new HashMap<>();
			variableList = new HashMap<>();
			
			flagList.put("true",true);
			flagList.put("false",false);
			variableList.put("male", (Object)"male");
			variableList.put("female", (Object)"female");
		}
		
		public History(String loadedData){
			
		}
		
		public Boolean getFlag(String flag){ 
			for(String p : flagList.keySet())
				if (p.equals(flag))
					return flagList.get(p);
			return null;
		}
	
		//creates the flag if no flag found
		public void setFlag(String flag, boolean val){
			for(String p : flagList.keySet())
				if(p.equals(flag)){
					flagList.put(p, val);
					return;
				}
			addFlag(flag, val);
		}
		
		public boolean addFlag(String flag, boolean val){ return flagList.put(flag, val); 	}
		
		public boolean setEvent(String event, String description){ 
			for(Pair<String, String> p : eventList)
				if (p.getKey().equals(event))
					return false;
			eventList.add(new Pair<>(event, description)); 
			return true;
		}
		
		public boolean findEvent(String event){ 
			for(Pair<String, String> p : eventList)
				if (p.getKey().equals(event))
					return true;
			return false;
		}
		
		public String getDescription(String event){
			for(Pair<String, String> p : eventList)
				if (p.getKey().equals(event))
					return p.getValue();
			return null;
		}
		
		public boolean declareVariable(String variableName, Object value){
			if (value instanceof Boolean) return addFlag(variableName, (Boolean)value);
			for(String p : variableList.keySet())
				if (p.equals(variableName))
					return false;
			if (!(value instanceof String) && !(value instanceof Integer) && !(value instanceof Float))
				return false;
			
			variableList.put(variableName, value);
			return true;
		}
		
		public Object getVariable(String variableName){
			for(String p : variableList.keySet())
				if (p.equals(variableName))
					return variableList.get(p);
			return null;                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        
		}
	
		public void setVariable(String var, Object val){
			for(String p : variableList.keySet())
				if(p.equals(var)){
					String type = variableList.get(p).getClass().getSimpleName();
					if(!var.getClass().getSimpleName().equals(type)){
						try{
							if(type.toLowerCase().equals("float"))
								variableList.put(p, (float) val);
							if(type.toLowerCase().equals("integer"))
								variableList.put(p, (int) val);
							if(type.toLowerCase().equals("string"))
								variableList.put(p, (String) val);
						} catch (Exception e){ }
					} else 
						variableList.put(p, val);
				}
		}
		
		//for use in the history section in the stats window
		//only includes major events
		public Texture getEventIcon(String event/*, String descriptor*/){
			switch(event){
			case "BrokeAnOldLadyCurse": return Game.res.getTexture("girlfriend1face");
			case "MetTheBadWitch": return Game.res.getTexture("witchface");
			case "RobbedTwoGangsters": return Game.res.getTexture("gangster1face");
			case "FellFromNowhere": return Game.res.getTexture(character.ID+"base");
			case "FoundTheNarrator": return Game.res.getTexture("narrator1face");
			default:
				return null;
			}
		}
	}
}


